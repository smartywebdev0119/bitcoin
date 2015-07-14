package com.jwetherell.bitcoin;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import com.jwetherell.bitcoin.data_model.Coin;
import com.jwetherell.bitcoin.data_model.Data;
import com.jwetherell.bitcoin.data_model.Wallet;
import com.jwetherell.bitcoin.interfaces.Listener;
import com.jwetherell.bitcoin.interfaces.Receiver;
import com.jwetherell.bitcoin.networking.Multicast;
import com.jwetherell.bitcoin.networking.UDP;

public class Peer {

    private static final boolean                DEBUG           = Boolean.getBoolean("debug");

    private static final int                    HEADER_LENGTH   = 8;
    private static final String                 WHOIS_MSG       = "Who is  ";
    private static final String                 IAM_MSG         = "I am    ";
    private static final String                 COIN_MSG        = "Coin    ";
    private static final String                 COIN_ACK        = "Coin ACK";

    private static final int                    NAME_LENGTH     = 4;

    private final UDP.Peer.RunnableSend         sendUdp         = new UDP.Peer.RunnableSend();
    private final Multicast.Peer.RunnableSend   sendMulti       = new Multicast.Peer.RunnableSend();

    private final Listener                      listener        = new Listener() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onMessage(Receiver recv) {
            Data d = recv.getQueue().poll();
            while (d != null) {
                final byte[] data = d.data.array();
                final String string = new String(data);
                String hdr = string.substring(0, HEADER_LENGTH);
                String body = string.substring(HEADER_LENGTH, data.length);
                if (DEBUG) 
                    System.out.println("Listener ("+name+") received '"+body+"'");
                if (hdr.equals(WHOIS_MSG)) {
                    handleWhois(data,d);
                } else if (hdr.equals(IAM_MSG)) {
                    String n = handleIam(data,d);
                    processPendingCoins(n);
                    processPendingCoinAcks(n);
                } else if (hdr.equals(COIN_MSG)) {
                    handleCoin(data);
                } else if (hdr.equals(COIN_ACK)) {
                    handleCoinAck(data);
                } else {
                    System.err.println("Cannot handle msg. hdr="+hdr+" body="+body);
                }
                d = recv.getQueue().poll();
            }
            Thread.yield();
        }
    };

    private final UDP.Peer.RunnableRecv         recvUdp         = new UDP.Peer.RunnableRecv(listener);
    private final Multicast.Peer.RunnableRecv   recvMulti       = new Multicast.Peer.RunnableRecv(listener);

    private final Map<String,Data>              peers           = new ConcurrentHashMap<String,Data>();
    private final Map<String,List<Coin>>        pendingCoins    = new ConcurrentHashMap<String,List<Coin>>();
    private final Map<String,List<Coin>>        pendingAcks     = new ConcurrentHashMap<String,List<Coin>>();

    private final String                        name;
    private final Wallet                        wallet;
    private final Thread                        udpSend;
    private final Thread                        udpRecv;
    private final Thread                        multiSend;
    private final Thread                        multiRecv;
    private final Queue<Data>                   sendUdpQueue;
    private final Queue<Data>                   sendMultiQueue;

    public Peer(String name) {
        this.name = name;
        this.wallet = new Wallet(name);

        udpSend = new Thread(sendUdp);
        this.sendUdpQueue = sendUdp.getQueue();
        udpSend.start();

        multiSend = new Thread(sendMulti);
        this.sendMultiQueue = sendMulti.getQueue();
        multiSend.start();

        udpRecv = new Thread(recvUdp);
        udpRecv.start();

        multiRecv = new Thread(recvMulti);
        multiRecv.start();
    }

    public void shutdown() throws InterruptedException {

        UDP.Peer.RunnableSend.run = false;
        UDP.Peer.RunnableRecv.run = false;
        Multicast.Peer.RunnableSend.run = false;
        Multicast.Peer.RunnableRecv.run = false;

        udpSend.interrupt();
        udpSend.join();

        udpRecv.interrupt();
        udpRecv.join();
        
        multiSend.interrupt();
        multiSend.join();
        
        multiRecv.interrupt();
        multiRecv.join();
    }

    public Wallet getWallet() {
        return wallet;
    }

    public Map<String,Data> getPeers() {
        return peers;
    }

    public static final byte[] getIamMsg(String name) {
        final byte[] bName = name.getBytes();
        final int nameLength = bName.length;
        final byte[] msg = new byte[HEADER_LENGTH + NAME_LENGTH + nameLength];
        final ByteBuffer buffer = ByteBuffer.wrap(msg);

        buffer.put(IAM_MSG.getBytes());
        buffer.putInt(nameLength);
        buffer.put(bName);

        return msg;
    }

    public static final String parseIamMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);

        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);

        final int nLength = buffer.getInt();
        final byte [] bName = new byte[nLength];
        buffer.get(bName);

        return new String(bName);
    }

    private void sendIam() {
        final byte[] msg = getIamMsg(name);
        final Data data = new Data(recvUdp.getHost(), recvUdp.getPort(), recvMulti.getHost(), recvMulti.getPort(), msg);
        sendMultiQueue.add(data);
    }

    private String handleIam(byte[] bytes, Data data) {
        final String name = parseIamMsg(bytes);

        // Ignore your own hello msg
        if (name.equals(this.name))
            return name;

        // Add peer
        peers.put(name, data);

        return name;
    }

    public static final byte[] getWhoisMsg(String name) {
        final byte[] bName = name.getBytes();
        final int nameLength = bName.length;
        final byte[] msg = new byte[HEADER_LENGTH + NAME_LENGTH + nameLength];
        final ByteBuffer buffer = ByteBuffer.wrap(msg);

        buffer.put(WHOIS_MSG.getBytes());
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

    private void sendWhois(String name) {
        final byte[] msg = getWhoisMsg(name);
        final Data data = new Data(recvUdp.getHost(), recvUdp.getPort(), recvMulti.getHost(), recvMulti.getPort(), msg);
        sendMultiQueue.add(data);
    }

    private void handleWhois(byte[] bytes, Data data) {
        final String name = parseWhoisMsg(bytes);

        // If your name then shout it out!
        if (name.equals(this.name))
            sendIam();
    }

    public static final byte[] getCoinMsg(Coin coin) {
        final byte[] msg = new byte[HEADER_LENGTH + coin.getBufferLength()];
        final ByteBuffer coinBuffer = ByteBuffer.allocate(coin.getBufferLength());
        coin.toBuffer(coinBuffer);
        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(COIN_MSG.getBytes());
        buffer.put(coinBuffer);

        return msg;
    }

    public static final Coin parseCoinMsg(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);
        final Coin coin = new Coin();
        coin.fromBuffer(buffer);

        return coin;
    }

    public void sendCoin(String name, int value) {
        final Coin coin = wallet.borrowCoin(name,value);
        final Data d = peers.get(name);
        if (d == null){
            // Could not find peer, broadcast a whois
            addPendingCoin(name,coin);
            sendWhois(name);
            return;
        }

        final byte[] msg = getCoinMsg(coin);
        final Data data = new Data(recvUdp.getHost(), recvUdp.getPort(), d.sourceAddr.getHostAddress(), d.sourcePort, msg);
        sendUdpQueue.add(data);
    }

    private void handleCoin(byte[] bytes) {
        final Coin coin = parseCoinMsg(bytes);

        // If not our coin, ignore
        if (!(name.equals(coin.to)))
            return;

        final String from = coin.from;
        wallet.addCoin(coin);

        ackCoin(from, coin);
    }

    public static final byte[] getCoinAck(Coin coin) {
        final byte[] msg = new byte[HEADER_LENGTH + coin.getBufferLength()];
        final ByteBuffer coinBuffer = ByteBuffer.allocate(coin.getBufferLength());
        coin.toBuffer(coinBuffer);
        final ByteBuffer buffer = ByteBuffer.wrap(msg);
        buffer.put(COIN_ACK.getBytes());
        buffer.put(coinBuffer);

        return msg;
    }

    public static final Coin parseCoinAck(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final byte [] bMsgType = new byte[HEADER_LENGTH];
        buffer.get(bMsgType);
        final Coin coin = new Coin();
        coin.fromBuffer(buffer);

        return coin;
    }

    public void ackCoin(String name, Coin coin) {
        final Data d = peers.get(name);
        if (d == null){
            // Could not find peer, broadcast a whois
            addPendingCoinAck(name,coin);
            sendWhois(name);
            return;
        }

        final byte[] msg = getCoinAck(coin);
        final Data data = new Data(recvUdp.getHost(), recvUdp.getPort(), d.sourceAddr.getHostAddress(), d.sourcePort, msg);
        sendUdpQueue.add(data);
    }

    private void handleCoinAck(byte[] bytes) {
        final Coin coin = parseCoinAck(bytes);
        wallet.removeBorrowedCoin(coin);
    }

    private void addPendingCoin(String n, Coin c) {
        List<Coin> l = pendingCoins.get(n);
        if (l == null) {
            l = new LinkedList<Coin>();
            pendingCoins.put(n, l);
        }
        l.add(c);
    }

    private void processPendingCoins(String n) {
        List<Coin> l = pendingCoins.get(n);
        if (l==null || l.size()==0)
            return;
        while (l.size()>0) {
            final Coin c = l.remove(0);
            final Data d = peers.get(n);
            if (d == null)
                return;
            final byte[] msg = getCoinMsg(c);
            final Data data = new Data(recvUdp.getHost(), recvUdp.getPort(), d.sourceAddr.getHostAddress(), d.sourcePort, msg);
            sendUdpQueue.add(data);
        }
    }

    private void addPendingCoinAck(String n, Coin c) {
        List<Coin> l = pendingAcks.get(n);
        if (l == null) {
            l = new LinkedList<Coin>();
            pendingAcks.put(n, l);
        }
        l.add(c);
    }

    private void processPendingCoinAcks(String n) {
        List<Coin> l = pendingAcks.get(n);
        if (l==null || l.size()==0)
            return;
        while (l.size()>0) {
            final Coin c = l.remove(0);
            final Data d = peers.get(n);
            if (d == null)
                return;
            final byte[] msg = getCoinAck(c);
            final Data data = new Data(recvUdp.getHost(), recvUdp.getPort(), d.sourceAddr.getHostAddress(), d.sourcePort, msg);
            sendUdpQueue.add(data);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("name=").append(name).append(" wallet={").append(wallet.toString()).append("}");
        return builder.toString();
    }
}
