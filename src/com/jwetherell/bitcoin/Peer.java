package com.jwetherell.bitcoin;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.jwetherell.bitcoin.common.Constants;
import com.jwetherell.bitcoin.common.HashUtils;
import com.jwetherell.bitcoin.data_model.Transaction;
import com.jwetherell.bitcoin.data_model.Data;
import com.jwetherell.bitcoin.data_model.Block;
import com.jwetherell.bitcoin.interfaces.MessageListener;
import com.jwetherell.bitcoin.interfaces.Receiver;
import com.jwetherell.bitcoin.networking.Multicast;
import com.jwetherell.bitcoin.networking.TCP;

/**
 * Class which handles lower level sending and receiving of messages.
 * 
 * Thread-Safe (Hopefully)
 */
public abstract class Peer {

    protected static final boolean                DEBUG                         = Boolean.getBoolean("debug");

    private static final int                      HEADER_LENGTH                 = 16;
    private static final String                   WHOIS                         = "Who is          ";
    private static final String                   IAM                           = "I am            ";
    private static final String                   TRANSACTION                   = "Transaction     ";
    private static final String                   TRANSACTION_ACK               = "Transaction ACK ";
    private static final String                   BLOCK                         = "Block           ";
    private static final String                   CONFIRMATION                  = "Block Confirm   ";
    private static final String                   RESEND                        = "Resend          ";
    private static final String                   REHASH                        = "Rehash          ";

    private static final int                      KEY_LENGTH                    = 4;
    private static final int                      NAME_LENGTH                   = 4;

    private static final String                   EVERY_ONE                     = "EVERYONE";
    private static final byte[]                   NO_SIG                        = new byte[0];

    private final TCP.Peer.RunnableSend           runnableSendTcp               = new TCP.Peer.RunnableSend();
    private final Multicast.Peer.RunnableSend     runnableSendMulti             = new Multicast.Peer.RunnableSend();

    private final MessageListener                 listener                      = new MessageListener() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onMessage(Receiver recv) {
            Data data = recv.getQueue().poll();
            while (data != null) {
                final String from = data.from;

                final byte[] bytes = data.message.array();
                final String string = new String(bytes);
                final String hdr = string.substring(0, HEADER_LENGTH);
                if (DEBUG) 
                    System.out.println(myName+" Listener received '"+hdr+"' msg");
                if (hdr.equals(WHOIS)) {
                    handleWhois(bytes,data);
                } else if (hdr.equals(IAM)) {
                    handleIam(bytes,data);
                    processTransactionsToSend(from);
                    processTransactionsToRecv(from);
                } else if (hdr.equals(TRANSACTION)) {
                    handleTransaction(from,bytes,data);
                } else if (hdr.equals(TRANSACTION_ACK)) {
                    handleTransactionAck(from,bytes,data);
                } else if (hdr.equals(BLOCK)) {
                    handleBlock(from,bytes,data);
                } else if (hdr.equals(CONFIRMATION)) {
                    handleConfirmation(from,bytes,data);
                    processFutureBlocksToRecv(from);
                } else if (hdr.equals(RESEND)) {
                    handleResend(from,bytes,data);
                } else if (hdr.equals(REHASH)) {
                    handleRehash(from,bytes,data);
                } else {
                    System.err.println(myName+" Cannot handle msg. hdr="+hdr);
                }

                // Get next message
                data = recv.getQueue().poll();
                Thread.yield();
            }
            Thread.yield();
        }
    };

    // Keep track of everyone's name -> ip+port
    private final Map<String,Host>                peers                         = new ConcurrentHashMap<String,Host>();

    // Pending msgs (This happens if we don't know the ip+port OR the public key of a host
    private final Map<String,Queue<Queued>>       transactionsToSend            = new ConcurrentHashMap<String,Queue<Queued>>();
    private final Map<String,Queue<Queued>>       transactionsToRecv            = new ConcurrentHashMap<String,Queue<Queued>>();
    private final Map<String,Queue<Queued>>       futureTransactionsToRecv      = new ConcurrentHashMap<String,Queue<Queued>>();

    private final Map<String,TimerTask>           timerMap                      = new ConcurrentHashMap<String,TimerTask>();
    private final Timer                           timer                         = new Timer();

    private final Thread                          tcpSendThread;
    private final Thread                          tcpRecvThread;
    private final Thread                          multiSendThread;
    private final Thread                          multiRecvThread;

    // Thread safe queues for sending messages
    private final Queue<Data>                     sendTcpQueue;
    private final Queue<Data>                     sendMultiQueue;

    protected final TCP.Peer.RunnableRecv         runnableRecvTcp               = new TCP.Peer.RunnableRecv(listener);
    protected final Multicast.Peer.RunnableRecv   runnableRecvMulti             = new Multicast.Peer.RunnableRecv(listener);
    protected final String                        myName;

    protected Peer(String name) {
        this.myName = name;

        // Receivers

        tcpRecvThread = new Thread(runnableRecvTcp, myName+" recvTcp");
        tcpRecvThread.start();

        multiRecvThread = new Thread(runnableRecvMulti, myName+" recvMulti");
        multiRecvThread.start();

        // Senders

        tcpSendThread = new Thread(runnableSendTcp, myName+" sendTcp");
        sendTcpQueue = runnableSendTcp.getQueue();
        tcpSendThread.start();

        multiSendThread = new Thread(runnableSendMulti, myName+" sendMulti");
        sendMultiQueue = runnableSendMulti.getQueue();
        multiSendThread.start();

        while (isReady()==false) {
            Thread.yield();
        }
    }

    public String getName() {
        return myName;
    }

    public void shutdown() throws InterruptedException {
        TCP.Peer.RunnableSend.run = false;
        TCP.Peer.RunnableRecv.run = false;

        Multicast.Peer.RunnableSend.run = false;
        Multicast.Peer.RunnableRecv.run = false;

        // Senders

        multiSendThread.interrupt();
        multiSendThread.join();

        tcpSendThread.interrupt();
        tcpSendThread.join();

        // Receivers

        tcpRecvThread.interrupt();
        tcpRecvThread.join();

        multiRecvThread.interrupt();
        multiRecvThread.join();
    }

    public boolean isReady() {
        return (runnableRecvTcp.isReady() && runnableSendTcp.isReady() && runnableRecvMulti.isReady() && runnableSendMulti.isReady());
    }

    public abstract Blockchain getBlockChain();

    /** Get encoded public key **/
    protected abstract byte[] getPublicKey();

    private void sendWhois(String who) {
        final byte[] msg = getWhoisMsg(who);
        final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), EVERY_ONE, runnableRecvMulti.getHost(), runnableRecvMulti.getPort(), NO_SIG, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg");
        }
        sendMultiQueue.add(data);
    }

    private void handleWhois(byte[] bytes, Data data) {
        final String name = parseWhoisMsg(bytes);

        // If your name and not from myself then shout it out!
        if (name.equals(this.myName) && !(data.from.equals(this.myName)))
            sendIam();
    }

    private void sendIam() {
        final byte[] msg = getIamMsg(getPublicKey());
        final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), EVERY_ONE, runnableRecvMulti.getHost(), runnableRecvMulti.getPort(), NO_SIG, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg");
        }
        sendMultiQueue.add(data);
    }

    private void handleIam(byte[] bytes, Data data) {
        final String name = data.from;
        final byte[] key = parseIamMsg(bytes);

        // Update peers
        peers.put(name, new Host(data.sourceAddr.getHostAddress(), data.sourcePort));

        // New public key
        newPublicKey(name, key);
    }

    /** What do you want to do with a new public key **/
    protected abstract void newPublicKey(String name, byte[] publicKey);

    /** Sign message with private key **/
    protected abstract byte[] signMsg(byte[] bytes);

    /** Verify the bytes given the public key and signature **/
    protected abstract boolean verifyMsg(byte[] publicKey, byte[] signature, byte[] bytes);

    /** Send transaction to the peer named 'to' **/
    protected void sendTransaction(String to, Transaction transaction) {
        final Host d = peers.get(to);
        if (d == null){
            // Could not find peer, broadcast a whois
            addTransactionToSend(Queued.State.NEW, to, transaction);
            sendWhois(to);
            return;
        }

        final byte[] msg = getTransactionMsg(transaction);
        final byte[] sig = signMsg(msg);
        final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), to, d.ip, d.port, sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg");
        }
        sendTcpQueue.add(data);
    }

    private Constants.Status handleTransaction(String dataFrom, byte[] bytes, Data data) {
        final Transaction trans = parseTransactionMsg(bytes);
        return handleTransaction(dataFrom, trans, data);
    }

    private Constants.Status handleTransaction(String dataFrom, Transaction transaction, Data data) {
        // I shouldn't ACK my own transaction
        if (transaction.from.equals(myName))
            return Constants.Status.OWN_TRANSACTION;

        // Let the app logic do what it needs to
        final Constants.Status status = handleTransaction(dataFrom, transaction, data.signature.array(), data.message.array());
        if (status == Constants.Status.NO_PUBLIC_KEY) {
            addTransactionToRecv(Queued.State.NEW, dataFrom, transaction, data);
            sendWhois(dataFrom);
            return status;
        } else if (status != Constants.Status.SUCCESS) {
            return status;
        }

        // Send an ACK msg
        ackTransaction(dataFrom, transaction);

        return status;
    }

    /** What do you want to do now that you have received a transaction, return the KeyStatus **/
    protected abstract Constants.Status handleTransaction(String dataFrom, Transaction transaction, byte[] signature, byte[] bytes);

    private void ackTransaction(String to, Transaction transaction) {
        final Host d = peers.get(to);
        if (d == null){
            // Could not find peer, broadcast a whois
            addTransactionToSend(Queued.State.ACK, to, transaction);
            sendWhois(to);
            return;
        }

        final byte[] msg = getTransactionAckMsg(transaction);
        final byte[] sig = signMsg(msg);
        final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), to, d.ip, d.port, sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg");
        }
        sendTcpQueue.add(data);
    }

    private void handleTransactionAck(String dataFrom, byte[] bytes, Data data) {
        final Transaction trans = parseTransactionAckMsg(bytes);
        handleTransactionAck(dataFrom, trans, data);
    }

    private void handleTransactionAck(String dataFrom, Transaction transaction, Data data) {
        // Let the app logic do what it needs to
        Constants.Status knownPublicKey = handleTransactionAck(dataFrom, transaction, data.signature.array(), data.message.array());
        if (knownPublicKey == Constants.Status.NO_PUBLIC_KEY) {
            addTransactionToRecv(Queued.State.ACK, dataFrom, transaction, data);
            sendWhois(dataFrom);
            return;
        } else if (knownPublicKey != Constants.Status.SUCCESS) {
            return;
        }

        final Block block = aggegateTransaction(transaction);
        if (block != null) 
            sendBlock(block, data);
    }

    /** What do you want to do now that you received an ACK for a sent transaction, return the KeyStatus **/
    protected abstract Constants.Status handleTransactionAck(String dataFrom, Transaction transaction, byte[] signature, byte[] bytes);

    /** Collect the transactions, either return null or a new Block **/
    protected abstract Block aggegateTransaction(Transaction transaction);

    protected void sendBlock(Block block, Data data) {
        final byte[] msg = getBlockMsg(block);
        final byte[] sig = signMsg(msg);
        final Data dataToSend = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), data.from, data.sourceAddr.getHostAddress(), data.sourcePort, sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg. confirmed="+block.confirmed);
        }
        sendTcpQueue.add(dataToSend);
    }

    private void handleBlock(String dataFrom, byte[] bytes, Data data) {
        final Block block = parseBlockMsg(bytes);
        handleBlock(dataFrom, block, data);
    }

    private void handleBlock(String dataFrom, Block block, Data data) {
        // I shouldn't handle my own block
        if (block.from.equals(myName))
            return;

        final int length = this.getBlockChain().getLength();
        final Constants.Status status = checkTransaction(data.from, block, data.signature.array(), data.message.array());
        if (status == Constants.Status.NO_PUBLIC_KEY) {
            addBlockToRecv(Queued.State.CONFIRM, dataFrom, block, data);
            sendWhois(dataFrom);
            return;
        } else if (status == Constants.Status.BAD_HASH) {
            sendRehash(block, data);
            return;
        } else if (status == Constants.Status.FUTURE_BLOCK) {
            // If we have a transaction which is in the future, we are likely missing a previous block
            // Save the block for later processing and ask the network for the missing block.
            addFutureBlockToRecv(Queued.State.FUTURE, dataFrom, block, data);
            sendResend(length);
            return;
        } else if (status != Constants.Status.SUCCESS) {
            System.out.println(myName+" handleBlock() error="+status);
            // Drop all other errors
            return;
        }

        // Hash looks good to me, ask everyone else
        sendConfirmation(block);
    }

    /** multicast **/
    protected void sendConfirmation(Block block) {
        final byte[] msg = getConfirmationMsg(block);
        final byte[] sig = signMsg(msg);
        final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), EVERY_ONE, runnableRecvMulti.getHost(), runnableRecvMulti.getPort(), sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending multicast '"+hdr+"' msg. confirmed="+block.confirmed);
        }
        sendMultiQueue.add(data);
    }

    /** tcp **/
    protected void sendConfirmation(Block block, Data data) {
        final byte[] msg = getConfirmationMsg(block);
        final byte[] sig = signMsg(msg);
        final Data dataToSend = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), data.from, data.sourceAddr.getHostAddress(), data.sourcePort, sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending unicast '"+hdr+"' msg. confirmed="+block.confirmed);
        }
        sendTcpQueue.add(dataToSend);
    }

    private void handleConfirmation(String dataFrom, byte[] bytes, Data data) {
        final Block block = parseConfirmationMsg(bytes);
        handleConfirmation(dataFrom, block, data);
    }

    private void handleConfirmation(String dataFrom, Block block, Data data) {
        if (block.confirmed) {
            // Yey! we got a confirmation from the community

            final int length = this.getBlockChain().getLength();
            final Constants.Status status = handleConfirmation(dataFrom, block, data.signature.array(), data.message.array());
            if (status == Constants.Status.SUCCESS) {
                // Someone solved the nonce, cancel async task - if we have one
                final String hex = HashUtils.bytesToHex(block.hash);
                final TimerTask miner = timerMap.get(hex);
                if (miner != null)
                    miner.cancel();
                return;
            } else if (status == Constants.Status.NO_PUBLIC_KEY) {
                addBlockToRecv(Queued.State.CONFIRM, dataFrom, block, data);
                sendWhois(dataFrom);
                return;
            } else if (status == Constants.Status.BAD_HASH) {
                sendRehash(block, data);
                return;
            } else if (status == Constants.Status.FUTURE_BLOCK) {
                // If we have a transaction which is in the future, we are likely missing a previous block
                // Save the block for later processing and ask the network for the missing block.
                addFutureBlockToRecv(Queued.State.FUTURE, dataFrom, block, data);
                sendResend(length);
                return;
            } else if (status != Constants.Status.SUCCESS) {
                // Drop all other errors
                return;
            }

            return;
        }

        // Don't confirm my own block
        if (block.from.equals(myName))
            return;

        final int length = this.getBlockChain().getLength();
        final Constants.Status status = checkTransaction(dataFrom, block, data.signature.array(), data.message.array());
        if (status == Constants.Status.NO_PUBLIC_KEY) {
            addBlockToRecv(Queued.State.CONFIRM, dataFrom, block, data);
            sendWhois(dataFrom);
            return;
        } else if (status == Constants.Status.FUTURE_BLOCK) {
            // Do not add to 'FutureBlockToRecv' since this block isn't confirmed
            sendResend(length);
            return;
        } else if (status == Constants.Status.BAD_HASH) {
            sendRehash(block, data);
            return;
        } else if (status != Constants.Status.SUCCESS) {
            // Drop all other errors
            return;
        }

        // async task
        final String hex = HashUtils.bytesToHex(block.hash);
        final TimerTask miningTask = new MiningTask(this,hex,block);
        timer.schedule(miningTask, 0);
    }

    /** What do you want to do now that you received a block, return the HashStatus **/
    protected abstract Constants.Status checkTransaction(String dataFrom, Block block, byte[] signature, byte[] bytes);

    /** What do you want to do now that you received a valid block, return the HashStatus **/
    protected abstract Constants.Status handleConfirmation(String dataFrom, Block block, byte[] signature, byte[] bytes);

    /** Mine the nonce sent in the transaction **/
    protected abstract int mineHash(MiningTask timer, byte[] sha256, long numberOfZerosInPrefix);

    private void sendResend(int blockNumber) {
        final byte[] msg = getResendBlockMsg(blockNumber);
        final byte[] sig = signMsg(msg);
        final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), EVERY_ONE, runnableRecvMulti.getHost(), runnableRecvMulti.getPort(), sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg");
        }
        sendMultiQueue.add(data);
    }

    private void handleResend(String dataFrom, byte[] bytes, Data data) {
        // I shouldn't resend from my own resend msg (flood warning!)
        if (dataFrom.equals(myName))
            return;

        final int blockNumber = parseResendBlockMsg(bytes);
        final int length = this.getBlockChain().getLength();
        // I don't have the block, cannot help.
        if (length<=blockNumber)
            return;

        final Block toSend = this.getBlockChain().getBlock(blockNumber);
        sendConfirmation(toSend, data);
    }

    protected void sendRehash(Block block, Data data) {
        final byte[] msg = getRehashMsg(block);
        final byte[] sig = signMsg(msg);
        final Data dataToSend = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), data.from, data.sourceAddr.getHostAddress(), data.sourcePort, sig, msg);
        if (DEBUG) {
            final String string = new String(msg);
            final String hdr = string.substring(0, HEADER_LENGTH);
            System.out.println(myName+" Sending '"+hdr+"' msg");
        }
        sendTcpQueue.add(dataToSend);
    }

    private void handleRehash(String dataFrom, byte[] bytes, Data data) {
        final Block block = parseRehashMsg(bytes);
        // hash was out of sync with the blockchain, reprocess it.
        for (Transaction t : block.transactions)
            handleTransactionAck(dataFrom, t, data);
    }

    private void addTransactionToSend(Queued.State state, String to, Transaction transaction) {
        final Queued q = new Queued(state, transaction, null);
        Queue<Queued> l = transactionsToSend.get(to);
        if (l == null) {
            l = new ConcurrentLinkedQueue<Queued>();
            transactionsToSend.put(to, l);
        }
        l.add(q);
    }

    private void processTransactionsToSend(String to) {
        Queue<Queued> l = transactionsToSend.get(to);
        if (l==null || l.size()==0)
            return;
        while (l.size()>0) {
            final Queued q = l.poll();
            if (q == null)
                return;
            final Host d = peers.get(to); // Do not use the data object in the queue object
            final byte[] msg = getTransactionMsg(q.transaction);
            final byte[] sig = signMsg(msg);
            final Data data = new Data(myName, runnableRecvTcp.getHost(), runnableRecvTcp.getPort(), to, d.ip, d.port, sig, msg);
            if (DEBUG) {
                final String string = new String(msg);
                final String hdr = string.substring(0, HEADER_LENGTH);
                System.out.println(myName+" Sending '"+hdr+"' msg");
            }
            sendTcpQueue.add(data);
        }
    }

    private void addTransactionToRecv(Queued.State state, String dataFrom, Transaction transaction, Data data) {
        final Queued q = new Queued(state, transaction, data);
        Queue<Queued> lc = transactionsToRecv.get(dataFrom);
        if (lc == null) {
            lc = new ConcurrentLinkedQueue<Queued>();
            transactionsToRecv.put(dataFrom, lc);
        }
        lc.add(q);
    }

    private void addBlockToRecv(Queued.State state, String dataFrom, Block block, Data data) {
        final Queued q = new Queued(state, block, data);
        Queue<Queued> lc = transactionsToRecv.get(dataFrom);
        if (lc == null) {
            lc = new ConcurrentLinkedQueue<Queued>();
            transactionsToRecv.put(dataFrom, lc);
        }
        lc.add(q);
    }

    private void processTransactionsToRecv(String from) {
        Queue<Queued> l = transactionsToRecv.get(from);
        if (l==null || l.size()==0)
            return;
        while (l.size()>0) {
            final Queued q = l.poll();
            if (q == null)
                return;
            if (q.state == Queued.State.CONFIRM)
                handleConfirmation(from, q.block, q.data);
            else if (q.state == Queued.State.ACK)
                handleTransactionAck(from, q.transaction, q.data);
            else
                handleTransaction(from, q.transaction, q.data);
        }
    }

    private void addFutureBlockToRecv(Queued.State state, String dataFrom, Block block, Data data) {
        final Queued q = new Queued(state, block, data);
        Queue<Queued> lc = futureTransactionsToRecv.get(dataFrom);
        if (lc == null) {
            lc = new ConcurrentLinkedQueue<Queued>();
            futureTransactionsToRecv.put(dataFrom, lc);
        }
        lc.add(q);
    }

    private void processFutureBlocksToRecv(String from) {
        Queue<Queued> l = futureTransactionsToRecv.get(from);
        if (l==null || l.size()==0)
            return;
        while (l.size()>0) {
            final Queued q = l.poll();
            if (q == null)
                return;
            if (q.state == Queued.State.FUTURE)
                handleConfirmation(from, q.block, q.data);
        }
    }

    private static final class Host {

        private final String    ip;
        private final int       port;

        private Host(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    private static final class Queued {

        private enum                State {NEW, ACK, CONFIRM, FUTURE};

        private final State         state;
        private final Transaction   transaction;
        private final Block         block;
        private final Data          data;

        private Queued(State state, Transaction transaction, Data data) {
            if (state == State.CONFIRM)
                throw new RuntimeException("Cannot have a CONFIRM without a Block");
            this.state = state;
            this.transaction = transaction;
            this.block = null;
            this.data = data;
        }

        private Queued(State state, Block block, Data data) {
            if (state != State.CONFIRM && state != State.FUTURE)
                throw new RuntimeException("Cannot have a non-CONFIRM/FUTURE with a Block");
            this.state = state;
            this.transaction = null;
            this.block = block;
            this.data = data;
        }
    }

    public static class MiningTask extends TimerTask {

        public volatile boolean run = true;

        private final   Peer    peer;
        private final   String  hex;
        private final   Block   block;

        /** unit testing constructor **/
        public MiningTask() {
            this.peer = null;
            this.hex = null;
            this.block = null;
        }

        public MiningTask(Peer peer, String hex, Block block) {
            this.peer = peer;
            this.hex = hex;
            this.block = block;

            TimerTask oldTask = peer.timerMap.put(hex, this);
            if (oldTask != null)
                oldTask.cancel();
        }

        @Override
        public void run() {
            peer.timerMap.remove(hex);

            // Let's mine this sucker.
            final int nonce = peer.mineHash(this, block.hash, block.numberOfZeros);

            if (nonce < 0)
                return;

            // Hash looks good to me and I have computed a nonce, let everyone know
            block.confirmed = true;
            block.nonce = nonce;

            // Make sure the block is still valid (hasn't been solved by someone else while we were processing)
            Constants.Status status = peer.getBlockChain().checkHash(block);
            if (status != Constants.Status.SUCCESS) {
                System.err.println(peer.myName +" NOT sending block, hash is no longer valid.");
                return;
            }

            peer.sendConfirmation(block);
        }

        @Override
        public boolean cancel() {
            peer.timerMap.remove(hex);
            run = false;
            return super.cancel();
        }
    }

    public static final byte[] getWhoisMsg(String name) {
        final byte[] bName = name.getBytes();
        final int nameLength = bName.length;
        final byte[] msg = new byte[HEADER_LENGTH + NAME_LENGTH + nameLength];
        final ByteBuffer buffer = ByteBuffer.wrap(msg);

        buffer.put(WHOIS.getBytes());
        buffer.putInt(nameLength);
        buffer.put(bName);

        return msg;
    }

    public static final String parseWhoisMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);

        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final int nLength = buffer.getInt();
        final byte [] bName = new byte[nLength];
        buffer.get(bName);

        return new String(bName);
    }

    public static final byte[] getIamMsg(byte[] publicKey) {
        final int publicKeyLength = publicKey.length;
        final byte[] msg = new byte[HEADER_LENGTH + KEY_LENGTH + publicKeyLength];
        final ByteBuffer buffer = ByteBuffer.wrap(msg);

        buffer.put(IAM.getBytes());

        buffer.putInt(publicKeyLength);
        buffer.put(publicKey);

        return msg;
    }

    public static final byte[] parseIamMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);

        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final int kLength = buffer.getInt();
        final byte [] bKey = new byte[kLength];
        buffer.get(bKey);

        return bKey;
    }

    public static final byte[] getTransactionMsg(Transaction transaction) {
        final byte[] msg = new byte[HEADER_LENGTH + transaction.getBufferLength()];
        final ByteBuffer transactionBuffer = ByteBuffer.allocate(transaction.getBufferLength());
        transaction.toBuffer(transactionBuffer);
        transactionBuffer.flip();

        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(TRANSACTION.getBytes());

        buffer.put(transactionBuffer);

        return msg;
    }

    public static final Transaction parseTransactionMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final Transaction transaction = new Transaction();
        transaction.fromBuffer(buffer);

        return transaction;
    }

    public static final byte[] getTransactionAckMsg(Transaction transaction) {
        final byte[] msg = new byte[HEADER_LENGTH + transaction.getBufferLength()];
        final ByteBuffer transactionBuffer = ByteBuffer.allocate(transaction.getBufferLength());
        transaction.toBuffer(transactionBuffer);
        transactionBuffer.flip();

        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(TRANSACTION_ACK.getBytes());

        buffer.put(transactionBuffer);

        return msg;
    }

    public static final Transaction parseTransactionAckMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final Transaction block = new Transaction();
        block.fromBuffer(buffer);

        return block;
    }

    public static final byte[] getBlockMsg(Block block) {
        final byte[] msg = new byte[HEADER_LENGTH + block.getBufferLength()];
        final ByteBuffer blockBuffer = ByteBuffer.allocate(block.getBufferLength());
        block.toBuffer(blockBuffer);
        blockBuffer.flip();

        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(BLOCK.getBytes());

        buffer.put(blockBuffer);

        return msg;
    }

    public static final Block parseBlockMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final Block block = new Block();
        block.fromBuffer(buffer);

        return block;
    }

    public static final byte[] getConfirmationMsg(Block block) {
        final byte[] msg = new byte[HEADER_LENGTH + block.getBufferLength()];
        final ByteBuffer blockBuffer = ByteBuffer.allocate(block.getBufferLength());
        block.toBuffer(blockBuffer);
        blockBuffer.flip();

        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(CONFIRMATION.getBytes());

        buffer.put(blockBuffer);

        return msg;
    }

    public static final Block parseConfirmationMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final Block block = new Block();
        block.fromBuffer(buffer);

        return block;
    }

    public static final byte[] getResendBlockMsg(int blockNumber) {
        final byte[] msg = new byte[HEADER_LENGTH + 4];

        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(RESEND.getBytes());

        buffer.putInt(blockNumber);

        return msg;
    }

    public static final int parseResendBlockMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final int blockNumber = buffer.getInt();

        return blockNumber;
    }

    public static final byte[] getRehashMsg(Block block) {
        final byte[] msg = new byte[HEADER_LENGTH + block.getBufferLength()];
        final ByteBuffer blockBuffer = ByteBuffer.allocate(block.getBufferLength());
        block.toBuffer(blockBuffer);
        blockBuffer.flip();

        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(REHASH.getBytes());

        buffer.put(blockBuffer);

        return msg;
    }

    public static final Block parseRehashMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final Block block = new Block();
        block.fromBuffer(buffer);

        return block;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("name=").append(myName);
        return builder.toString();
    }
}
