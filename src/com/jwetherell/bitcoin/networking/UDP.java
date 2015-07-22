package com.jwetherell.bitcoin.networking;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.jwetherell.bitcoin.data_model.Data;
import com.jwetherell.bitcoin.interfaces.MessageListener;
import com.jwetherell.bitcoin.interfaces.Receiver;
import com.jwetherell.bitcoin.interfaces.Sender;

public class UDP {

    private static final boolean    DEBUG       = Boolean.getBoolean("debug_all");

    public static final String      LOCAL       = "127.0.0.1";

    public static int               port        = 1111;

    public static DatagramSocket createServer(int port) throws SocketException {
        final DatagramSocket serverSocket = new DatagramSocket(port);
        return serverSocket;
    }

    public static void destoryServer(DatagramSocket s) {
        if (s != null)
            s.close();
    }

    public static DatagramSocket createClient() throws SocketException {
        final DatagramSocket clientSocket = new DatagramSocket();
        return clientSocket;
    }

    public static void destoryClient(DatagramSocket s) {
        if (s != null)
            s.close();
    }

    public static void sendData(DatagramSocket socket, InetAddress IPAddress, int port, byte[] buffer) throws IOException {
        final DatagramPacket sendPacket = new DatagramPacket(buffer, buffer.length, IPAddress, port);
        socket.send(sendPacket);
    }

    /**
     * Blocking call
     */
    public static boolean recvData(DatagramSocket socket, byte[] buffer) throws IOException {
        socket.setSoTimeout(100);
        final DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(receivePacket);
        } catch (SocketTimeoutException e) {
            return false;
        }
        return true;
    }

    public static final class Peer { 

        private static final int        BUFFER_SIZE     = 10*1024;

        private Peer() { }

        public static final class RunnableRecv implements Runnable, Receiver {

            public static volatile boolean                      run         = true;

            private final ConcurrentLinkedQueue<Data>           toRecv      = new ConcurrentLinkedQueue<Data>();
            private final int                                   port;
            private final MessageListener                       listener;

            private volatile boolean                            isReady     = false;

            public RunnableRecv(MessageListener listener) {
                run = true;
                this.port = UDP.port++;
                this.listener = listener;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public Queue<Data> getQueue() {
                return toRecv;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean isReady() {
                return isReady;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public String getHost() {
                return LOCAL;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public int getPort() {
                return port;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void run() {
                DatagramSocket s = null;
                try {
                    if (DEBUG) 
                        System.out.println("Creating server. port="+port);
                    s = UDP.createServer(port);
                    final byte[] array = new byte[BUFFER_SIZE];
                    final ByteBuffer bb = ByteBuffer.wrap(array);
                    isReady = true;
                    while (run) {
                        bb.clear();
                        final boolean p = UDP.recvData(s,bb.array());
                        if (!p) {
                            Thread.yield();
                            continue;
                        }

                        final Data data = new Data();
                        data.fromBuffer(bb);

                        if (DEBUG) 
                            System.out.println("Server ("+getHost()+":"+getPort()+") received '"+new String(data.message.array())+"' from "+data.sourceAddr.getHostAddress()+":"+data.sourcePort);

                        toRecv.add(data);
                        listener.onMessage(this);

                        Thread.yield();
                    }
                } catch (SocketException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    UDP.destoryServer(s);
                }
            }
        };

        public static final class RunnableSend implements Runnable, Sender {

            public static volatile boolean                      run         = true;

            private final ConcurrentLinkedQueue<Data>           toSend      = new ConcurrentLinkedQueue<Data>();

            private volatile boolean                            isReady     = false;

            public RunnableSend() {
                run = true;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public Queue<Data> getQueue() {
                return toSend;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean isReady() {
                return isReady;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void run() {
                DatagramSocket s = null;
                try {
                    if (DEBUG) 
                        System.out.println("Creating client");
                    s = UDP.createClient();
                    final byte[] buffer = new byte[BUFFER_SIZE];
                    final ByteBuffer bb = ByteBuffer.wrap(buffer);
                    isReady = true;
                    while (run) {
                        if (DEBUG && toSend.size()>1)
                            System.out.println("Client toSend size="+toSend.size());
                        final Data d = toSend.poll();
                        if (d != null) {
                            bb.clear();
                            d.toBuffer(bb);
                            bb.flip();

                            if (DEBUG) 
                                System.out.println("Client ("+d.sourceAddr.getHostAddress()+":"+d.sourcePort+") sending '"+new String(d.message.array())+"'");

                            UDP.sendData(s, d.destAddr, d.destPort, buffer);
                        }

                        Thread.yield();
                    }
                } catch (SocketException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally { 
                    if (s != null)
                        UDP.destoryClient(s);
                }
            }
        };
    }
}
