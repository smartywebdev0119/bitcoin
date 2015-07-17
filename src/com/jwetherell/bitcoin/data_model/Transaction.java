package com.jwetherell.bitcoin.data_model;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class Transaction {

    private static final int    BOOLEAN_LENGTH  = 2;
    private static final int    LENGTH_LENGTH   = 4;

    public boolean              isValid         = false;
    public Coin                 coin;
    public byte[]               prev;
    public byte[]               hash;

    public Transaction() {
        coin = new Coin();
        prev = new byte[]{};
        hash = new byte[]{};
    }

    public Transaction(String from, byte[] prevHash, byte[] hash, Coin coin) {
        this.prev = prevHash;
        this.hash = hash;
        this.coin = coin;
    }

    public int getBufferLength() {
        return  BOOLEAN_LENGTH + 
                LENGTH_LENGTH + prev.length + 
                LENGTH_LENGTH + hash.length + 
                coin.getBufferLength();
    }

    public void toBuffer(ByteBuffer buffer) {
        buffer.putChar(getBoolean(isValid));

        buffer.putInt(prev.length);
        buffer.put(prev);

        buffer.putInt(hash.length);
        buffer.put(hash);

        coin.toBuffer(buffer);
    }

    public void fromBuffer(ByteBuffer buffer) {
        isValid = parseBoolean(buffer.getChar());

        {
            final int length = buffer.getInt();
            prev = new byte[length];
            buffer.get(prev);
        }

        {
            final int length = buffer.getInt();
            hash = new byte[length];
            buffer.get(hash);
        }

        coin.fromBuffer(buffer);
    }

    private static final char getBoolean(boolean bool) {
        return (bool?'T':'F');
    }

    private static final boolean parseBoolean(char bool) {
        return (bool=='T'?true:false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Transaction))
            return false;
        Transaction c = (Transaction) o;
        if (isValid != c.isValid)
            return false;
        if (!(c.coin.equals(this.coin)))
            return false;
        if (!(Arrays.equals(c.prev, prev)))
            return false;
        if (!(Arrays.equals(c.hash, hash)))
            return false;
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("isValid=").append(isValid).append("\n");
        builder.append("coin={").append("\n");
        builder.append(coin.toString()).append("\n");
        builder.append("}");
        return builder.toString();
    }
}