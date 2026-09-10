package com.ylmac.adapt.features.music.netease;

import java.math.BigInteger;

final class X25519 {

    private static final BigInteger P =
            BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private X25519() {}

    static byte[] scalarMultBase(byte[] scalar) {
        byte[] u = new byte[32];
        u[0] = 9;
        return scalarMult(scalar, u);
    }

    static byte[] scalarMult(byte[] scalar, byte[] uBytes) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= (byte) 64;
        BigInteger kk = leToBig(k);

        byte[] ub = uBytes.clone();
        ub[31] &= (byte) 127;
        BigInteger x1 = leToBig(ub);

        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO;
        BigInteger x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int kt = kk.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp;
                tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);

            BigInteger x3n = da.add(cb).mod(P);
            x3 = x3n.multiply(x3n).mod(P);
            BigInteger z3n = da.subtract(cb).mod(P);
            z3 = x1.multiply(z3n.multiply(z3n).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P);
        }

        if (swap == 1) {
            BigInteger tmp;
            tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }

        BigInteger res = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P);
        return bigToLe(res);
    }

    private static BigInteger leToBig(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    private static byte[] bigToLe(BigInteger n) {
        byte[] out = new byte[32];
        byte[] be = n.toByteArray();
        for (int i = 0; i < be.length; i++) {
            int idx = be.length - 1 - i;
            if (i < 32) out[i] = be[idx];
        }
        return out;
    }
}
