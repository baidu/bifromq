/*
 * Copyright (c) 2023. Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.basekv.raft;

import com.baidu.bifromq.basekv.raft.exception.ClusterConfigChangeException;
import com.baidu.bifromq.basekv.raft.exception.DropProposalException;
import com.baidu.bifromq.basekv.raft.exception.LeaderTransferException;
import com.baidu.bifromq.basekv.raft.exception.ReadIndexException;
import com.baidu.bifromq.basekv.raft.exception.RecoveryException;
import com.baidu.bifromq.basekv.raft.proto.ClusterConfig;
import com.baidu.bifromq.basekv.raft.proto.LogEntry;
import com.baidu.bifromq.basekv.raft.proto.RaftMessage;
import com.baidu.bifromq.basekv.raft.proto.RaftNodeStatus;
import com.baidu.bifromq.basekv.raft.proto.RequestPreVote;
import com.baidu.bifromq.basekv.raft.proto.RequestVote;
import com.google.protobuf.ByteString;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;

class RaftNodeStateCandidate extends RaftNodeState {
    private final QuorumTracker voteTracker;
    private final QuorumTracker preVoteTracker;
    private int electionElapsedTick;
    private int randomElectionTimeoutTick;
    private boolean checkRecoverable = false;
    private CompletableFuture<Void> recoveryTask;

    RaftNodeStateCandidate(long term,
                           long commitIndex,
                           RaftConfig config,
                           IRaftStateStore stateStorage,
                           Logger log,
                           LinkedHashMap<Long, ProposeTask> uncommittedProposals,
                           IRaftNode.IRaftMessageSender sender,
                           IRaftNode.IRaftEventListener listener,
                           IRaftNode.ISnapshotInstaller installer,
                           OnSnapshotInstalled onSnapshotInstalled,
                           String... tags) {
        super(term,
            commitIndex,
            config,
            stateStorage,
            log,
            uncommittedProposals,
            sender,
            listener,
            installer,
            onSnapshotInstalled,
            tags);
        randomElectionTimeoutTick = randomizeElectionTimeoutTick();
        voteTracker = new QuorumTracker(stateStorage.latestClusterConfig(), this);
        preVoteTracker = new QuorumTracker(stateStorage.latestClusterConfig(), this);
    }

    @Override
    RaftNodeStatus getState() {
        return RaftNodeStatus.Candidate;
    }

    @Override
    String currentLeader() {
        // no leader available in candidate state
        return null;
    }

    @Override
    RaftNodeState stepDown() {
        return this;
    }

    @Override
    RaftNodeState recover(CompletableFuture<Void> onDone) {
        if (!promotable()) {
            onDone.completeExceptionally(RecoveryException.notVoter());
        } else if (recoveryTask == null) {
            checkRecoverable = false;
            recoveryTask = onDone;
        } else {
            onDone.completeExceptionally(RecoveryException.recoveryInProgress());
        }
        return this;
    }

    @Override
    RaftNodeState tick() {
        electionElapsedTick++;
        if (promotable() && electionElapsedTick >= randomElectionTimeoutTick) {
            randomElectionTimeoutTick = randomizeElectionTimeoutTick();
            logDebug("No leader elected, reset election timeout[{}]", randomElectionTimeoutTick);
            if (recoveryTask != null && checkRecoverable) {
                tryRecovery();
            }
            return campaign(config.isPreVote(), false);
        }
        return this;
    }

    @Override
    void propose(ByteString fsmCmd, CompletableFuture<Long> onDone) {
        onDone.completeExceptionally(DropProposalException.NoLeader());
    }

    @Override
    RaftNodeState stableTo(long stabledIndex) {
        return this;
    }

    @Override
    void readIndex(CompletableFuture<Long> onDone) {
        onDone.completeExceptionally(ReadIndexException.noLeader());
    }

    @Override
    RaftNodeState receive(String fromPeer, RaftMessage message) {
        logTrace("Receive[{}] from {}", message, fromPeer);
        RaftNodeState nextState = this;
        if (message.getTerm() > currentTerm()) {
            switch (message.getMessageTypeCase()) {
                case REQUESTPREVOTE -> handlePreVote(fromPeer, message.getTerm(), message.getRequestPreVote());
                case REQUESTPREVOTEREPLY -> {
                    // update pre-vote tracker and tally pre-votes
                    preVoteTracker.poll(fromPeer, message.getRequestPreVoteReply().getVoteCouldGranted());
                    QuorumTracker.JointVoteResult preVoteResult = preVoteTracker.tally();
                    switch (preVoteResult.result) {
                        case Won -> {
                            // initiate formal campaign
                            finishRecoveryTask(RecoveryException.notLostQuorum());
                            logDebug("Pre-Election won[{}] and started formal campaign", preVoteResult);
                            nextState = campaign(false, false);
                        }
                        case Lost -> {
                            finishRecoveryTask(RecoveryException.notLostQuorum());
                            logDebug("Pre-Election lost[{}] and stay as candidate", preVoteResult);
                        }
                    }
                } // don't fall through
                // fallthrough to become follower at new term if pre-vote is not granted
                default -> {
                    // higher term found and transition to follower according to $3.3 in raft paper
                    logDebug("Transited to follower due to higher term[{}] found", message.getTerm());
                    finishRecoveryTask(RecoveryException.abort());
                    nextState = new RaftNodeStateFollower(
                        message.getTerm(), // update term
                        commitIndex,
                        null, // no leader for new term
                        config,
                        stateStorage,
                        log,
                        uncommittedProposals,
                        sender,
                        listener,
                        snapshotInstaller,
                        onSnapshotInstalled,
                        tags
                    );
                    nextState.receive(fromPeer, message);
                }
            }
        } else if (message.getTerm() < currentTerm()) {
            handleLowTermMessage(fromPeer, message);
            return nextState;
        } else {
            // term match, leader of this term has been elected
            switch (message.getMessageTypeCase()) {
                case APPENDENTRIES, INSTALLSNAPSHOT -> {
                    String leader = message.getMessageTypeCase() == RaftMessage.MessageTypeCase.APPENDENTRIES ?
                        message.getAppendEntries().getLeaderId() : message.getInstallSnapshot().getLeaderId();
                    logDebug("Transited to follower to handle request from newly elected leader[{}]", leader);
                    finishRecoveryTask(RecoveryException.notLostQuorum());
                    nextState = new RaftNodeStateFollower(
                        message.getTerm(),
                        commitIndex,
                        leader, // leader elected
                        config,
                        stateStorage,
                        log,
                        uncommittedProposals,
                        sender,
                        listener,
                        snapshotInstaller,
                        onSnapshotInstalled,
                        tags
                    );
                    nextState.receive(fromPeer, message);
                }
                case REQUESTVOTEREPLY -> {
                    // update vote tracker and tally votes
                    voteTracker.poll(fromPeer, message.getRequestVoteReply().getVoteGranted());
                    nextState = tallyVotes();
                }
                case REQUESTVOTE -> {
                    // reject since candidate always votes for itself
                    logDebug("Rejected vote for candidate[{}]", fromPeer);
                    sendRequestVoteReply(fromPeer, currentTerm(), false);
                }
            }
        }
        return nextState;
    }

    @Override
    void transferLeadership(String newLeader, CompletableFuture<Void> onDone) {
        onDone.completeExceptionally(LeaderTransferException.noLeader());
    }

    @Override
    void changeClusterConfig(String correlateId,
                             Set<String> newVoters,
                             Set<String> newLearners,
                             CompletableFuture<Void> onDone) {
        onDone.completeExceptionally(ClusterConfigChangeException.noLeader());
    }

    @Override
    void onSnapshotRestored(ByteString fsmSnapshot, Throwable ex) {
    }

    RaftNodeState campaign(boolean preVote, boolean transferLeader) {
        if (recoveryTask != null && !checkRecoverable) {
            checkRecoverable = true;
        }
        electionElapsedTick = 0;
        if (!promotable()) {
            // keep staying as a mute candidate(don't be disruptive)
            return this;
        }
        // if campaigning for transferring leader, no need to pre-vote no matter what preVote is
        voteTracker.reset();
        preVoteTracker.reset();
        long campaignTerm = currentTerm() + 1; // campaign for next term
        if (transferLeader || !preVote) {
            stateStorage.saveTerm(currentTerm() + 1);
        }
        long lastLogTerm = stateStorage.latestSnapshot().getTerm();
        long lastLogIndex = stateStorage.latestSnapshot().getIndex();
        if (stateStorage.lastIndex() >= stateStorage.firstIndex()) {
            LogEntry lastEntry = stateStorage.entryAt(stateStorage.lastIndex()).get();
            lastLogIndex = lastEntry.getIndex();
            lastLogTerm = lastEntry.getTerm();
        }
        RaftMessage.Builder msgBuilder = RaftMessage.newBuilder()
            .setTerm(campaignTerm);
        logDebug("Started leadership campaign for term[{}] with pre-vote[{}] and transferLeader[{}]",
            campaignTerm, preVote, transferLeader);
        if (transferLeader || !preVote) {
            // vote for myself
            voteTracker.poll(stateStorage.local(), true);
            msgBuilder.setRequestVote(RequestVote.newBuilder()
                .setCandidateId(stateStorage.local())
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .setLeaderTransfer(transferLeader)
                .build());
        } else {
            // pre-vote for myself
            preVoteTracker.poll(stateStorage.local(), true);
            msgBuilder.setRequestPreVote(RequestPreVote.newBuilder()
                .setCandidateId(stateStorage.local())
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build());
        }

        Map<String, List<RaftMessage>> requests = new HashMap<>();
        Set<String> peers = remoteVoters();
        if (!peers.isEmpty()) {
            List<RaftMessage> msg = Collections.singletonList(msgBuilder.build());
            peers.forEach(peer -> requests.put(peer, msg));
            submitRaftMessages(requests);
        }
        if (transferLeader || !preVote) {
            return tallyVotes();
        } else {
            QuorumTracker.JointVoteResult preVoteResult = preVoteTracker.tally();
            switch (preVoteResult.result) {
                case Won -> {
                    // initiate formal campaign
                    logDebug("Pre-Election won[{}] and started formal campaign", preVoteResult);
                    finishRecoveryTask(RecoveryException.notLostQuorum());
                    return campaign(false, false);
                }
                case Lost -> {
                    logDebug("Pre-Election lost[{}] and transited to follower", preVoteResult);
                    finishRecoveryTask(RecoveryException.notLostQuorum());
                    return new RaftNodeStateFollower(
                        currentTerm(), // same term
                        commitIndex,
                        null, // no leader elected
                        config,
                        stateStorage,
                        log,
                        uncommittedProposals,
                        sender,
                        listener,
                        snapshotInstaller,
                        onSnapshotInstalled,
                        tags
                    );
                }
                default -> {
                    return this;
                }
            }
        }
    }

    private void tryRecovery() {
        QuorumTracker quorumTracker = config.isPreVote() ? preVoteTracker : voteTracker;
        Set<String> allVoters = voters();
        QuorumTracker.VoteGroupResult tallyResult = quorumTracker.tally(allVoters);
        if (tallyResult.no == 0) {
            ClusterConfig recoveryConfig = ClusterConfig.newBuilder()
                .addAllVoters(allVoters.stream()
                    .filter(v -> quorumTracker.tally(v) == QuorumTracker.TallyResult.Yes)
                    .collect(Collectors.toSet()))
                // inherit all learners?
                .build();
            long lastLogTerm = stateStorage.latestSnapshot().getTerm();
            long lastLogIndex = stateStorage.latestSnapshot().getIndex();
            if (stateStorage.lastIndex() >= stateStorage.firstIndex()) {
                LogEntry lastEntry = stateStorage.entryAt(stateStorage.lastIndex()).get();
                lastLogIndex = lastEntry.getIndex();
                lastLogTerm = lastEntry.getTerm();
            }
            LogEntry targetConfigEntry = LogEntry.newBuilder()
                .setTerm(lastLogTerm)
                .setIndex(lastLogIndex + 1)
                .setConfig(recoveryConfig)
                .build();
            // flush the log entry immediately
            logInfo("Recover cluster config to {}", recoveryConfig.getVotersList());
            stateStorage.append(Collections.singletonList(targetConfigEntry), true);
            preVoteTracker.refresh(recoveryConfig);
            voteTracker.refresh(recoveryConfig);
            finishRecoveryTask(null);
        } else {
            finishRecoveryTask(RecoveryException.notQualify());
        }
    }

    private void finishRecoveryTask(RecoveryException ex) {
        if (recoveryTask != null) {
            if (ex == null) {
                recoveryTask.complete(null);
            } else {
                recoveryTask.completeExceptionally(ex);
            }
            recoveryTask = null;
            checkRecoverable = false;
        }
    }

    private RaftNodeState tallyVotes() {
        QuorumTracker.JointVoteResult voteResult = voteTracker.tally();
        switch (voteResult.result) {
            case Won:
                // win election
                logDebug("Election won[{}] and stepped up to leader", voteResult);
                finishRecoveryTask(RecoveryException.notLostQuorum());
                return new RaftNodeStateLeader(
                    currentTerm(),
                    commitIndex,
                    config,
                    stateStorage,
                    log,
                    uncommittedProposals,
                    sender,
                    listener,
                    snapshotInstaller,
                    onSnapshotInstalled,
                    tags
                );
            case Lost:
                // lost election
                logDebug("Election lost[{}] and transited to follower", voteResult);
                finishRecoveryTask(RecoveryException.notLostQuorum());
                return new RaftNodeStateFollower(
                    currentTerm(),
                    commitIndex,
                    null,
                    config,
                    stateStorage,
                    log,
                    uncommittedProposals,
                    sender,
                    listener,
                    snapshotInstaller,
                    onSnapshotInstalled,
                    tags);
            case Pending:
                logDebug("Election pending[{}]", voteResult);
                // fallthrough
            default:
                return this;
        }
    }
}
