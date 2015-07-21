package com.jwetherell.bitcoin.test;

import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;

import com.jwetherell.bitcoin.data_model.Transaction;
import com.jwetherell.bitcoin.data_model.Block;

public class BlockTest {

    private static final Transaction[]  EMPTY       = new Transaction[0];
    private static final byte[]         SIGNATURE   = "sig".getBytes();


    @Test
    public void testSerialization() {
        final String f = "me";
        final String t = "you";
        final String m = "Here is a coin for you!";

        final Transaction trans = new Transaction(f, t, m, 10, SIGNATURE, EMPTY, EMPTY);
        final Transaction[] transactions = new Transaction[]{ trans };
        byte[] prev = "I am a hash!".getBytes();
        byte[] hash = "I am also a hash!".getBytes();
        final Block block = new Block(f,prev,hash,transactions,1);
        final ByteBuffer buffer = ByteBuffer.allocate(block.getBufferLength());
        block.toBuffer(buffer);
        buffer.flip();

        final Block block2 = new Block();
        block2.fromBuffer(buffer);

        Assert.assertTrue(block.equals(block2));
    }
}
