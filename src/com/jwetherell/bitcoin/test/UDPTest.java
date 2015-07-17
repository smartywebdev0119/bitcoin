package com.jwetherell.bitcoin.test;

import org.junit.Assert;
import org.junit.Test;

import com.jwetherell.bitcoin.data_model.Data;
import com.jwetherell.bitcoin.interfaces.Listener;
import com.jwetherell.bitcoin.interfaces.Receiver;
import com.jwetherell.bitcoin.networking.UDP;

public class UDPTest {

    @Test(timeout=5000)
    public void test() throws InterruptedException {
        final String from = "me";
        final String to = "you";
        final byte[] sig = "sig".getBytes();
        final byte[] toSend = "Hello world.".getBytes();
        final Listener listener = new Listener() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void onMessage(Receiver recv) {
                Data d = recv.getQueue().poll();
                while (d != null) {
                    final byte[] data = d.message.array();
                    System.out.println("Listener received '"+new String(data)+"'");
                    Assert.assertTrue(isEquals(toSend,data,toSend.length));
                    d = recv.getQueue().poll();
                }
                UDP.Peer.RunnableRecv.run = false;
                UDP.Peer.RunnableSend.run = false;
            }
        };

        // Start both the sender and receiver
        final UDP.Peer.RunnableRecv recv = new UDP.Peer.RunnableRecv(listener);
        final Thread r = new Thread(recv,"recv");
        r.start();     

        final UDP.Peer.RunnableSend send = new UDP.Peer.RunnableSend();
        final Thread s = new Thread(send,"send");
        s.start();

        // Wait for everyone to initialize
        while (recv.isReady()==false || send.isReady()==false) {
            Thread.yield();
        }

        final Data data = new Data(from ,recv.getHost(), recv.getPort(), to, recv.getHost(), recv.getPort(), sig, toSend);
        send.getQueue().add(data);

        // Wait for threads to finish
        r.join();
        s.join();
    }

    private static final boolean isEquals(byte[] a, byte[] b, int length) {
        for (int i=0; i<length; i++)
            if (a[i] != b[i])
                return false;
        return true;
    }
}
