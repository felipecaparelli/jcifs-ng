package jcifs.smb;


import java.security.MessageDigest;
import java.util.Date;

import org.apache.log4j.Logger;

import jcifs.Configuration;
import jcifs.SmbConstants;
import jcifs.util.Crypto;
import jcifs.util.Hexdump;


/**
 * To filter 0 len updates and for debugging
 */

public class SigningDigest {

    private static final Logger log = Logger.getLogger(SigningDigest.class);

    private MessageDigest digest;
    private byte[] macSigningKey;
    private boolean bypass = false;
    private int updates;
    private int signSequence;


    public SigningDigest ( byte[] macSigningKey, boolean bypass ) {
        this.digest = Crypto.getMD5();
        this.macSigningKey = macSigningKey;
        this.bypass = bypass;
        this.updates = 0;
        this.signSequence = 0;

        if ( log.isTraceEnabled() ) {
            log.trace("macSigningKey:");
            log.trace(Hexdump.toHexString(macSigningKey, 0, macSigningKey.length));
        }
    }


    /**
     * This constructor used to instance a SigningDigest object for
     * signing/verifying SMB using kerberos session key.
     * The MAC Key = concat(Session Key, Digest of Challenge);
     * Because of Kerberos Authentication don't have challenge,
     * The MAC Key = Session Key
     * 
     * @param macSigningKey
     *            The MAC key used to sign or verify SMB.
     * @throws SmbException
     *             When failed to instance MessageDigest with "MD5" algorithm.
     */
    public SigningDigest ( byte[] macSigningKey ) {
        this.digest = Crypto.getMD5();
        this.macSigningKey = macSigningKey;
    }


    public SigningDigest ( SmbTransport transport, NtlmPasswordAuthentication auth ) throws SmbException {
        this.digest = Crypto.getMD5();
        try {
            switch ( transport.getTransportContext().getConfig().getLanManCompatibility() ) {
            case 0:
            case 1:
            case 2:
                this.macSigningKey = new byte[40];
                auth.getUserSessionKey(transport.getTransportContext(), transport.server.encryptionKey, this.macSigningKey, 0);
                System.arraycopy(auth.getUnicodeHash(transport.getTransportContext(), transport.server.encryptionKey), 0, this.macSigningKey, 16, 24);
                break;
            case 3:
            case 4:
            case 5:
                this.macSigningKey = new byte[16];
                auth.getUserSessionKey(transport.getTransportContext(), transport.server.encryptionKey, this.macSigningKey, 0);
                break;
            default:
                this.macSigningKey = new byte[40];
                auth.getUserSessionKey(transport.getTransportContext(), transport.server.encryptionKey, this.macSigningKey, 0);
                System.arraycopy(auth.getUnicodeHash(transport.getTransportContext(), transport.server.encryptionKey), 0, this.macSigningKey, 16, 24);
                break;
            }
        }
        catch ( Exception ex ) {
            throw new SmbException("", ex);
        }
        if ( log.isTraceEnabled() ) {
            log.trace("LM_COMPATIBILITY=" + transport.getTransportContext().getConfig().getLanManCompatibility());
            log.trace(Hexdump.toHexString(this.macSigningKey, 0, this.macSigningKey.length));
        }
    }


    public void update ( byte[] input, int offset, int len ) {
        if ( log.isTraceEnabled() ) {
            log.trace("update: " + this.updates + " " + offset + ":" + len);
            log.trace(Hexdump.toHexString(input, offset, Math.min(len, 256)));
        }
        if ( len == 0 ) {
            return; /* CRITICAL */
        }
        this.digest.update(input, offset, len);
        this.updates++;
    }


    public byte[] digest () {
        byte[] b;

        b = this.digest.digest();

        if ( log.isTraceEnabled() ) {
            log.trace("digest: ");
            log.trace(Hexdump.toHexString(b, 0, b.length));
        }
        this.updates = 0;

        return b;
    }


    /**
     * Performs MAC signing of the SMB. This is done as follows.
     * The signature field of the SMB is overwritted with the sequence number;
     * The MD5 digest of the MAC signing key + the entire SMB is taken;
     * The first 8 bytes of this are placed in the signature field.
     *
     * @param data
     *            The data.
     * @param offset
     *            The starting offset at which the SMB header begins.
     * @param length
     *            The length of the SMB data starting at offset.
     */
    void sign ( byte[] data, int offset, int length, ServerMessageBlock request, ServerMessageBlock response ) {
        request.signSeq = this.signSequence;
        if ( response != null ) {
            response.signSeq = this.signSequence + 1;
            response.verifyFailed = false;
        }

        try {
            update(this.macSigningKey, 0, this.macSigningKey.length);
            int index = offset + SmbConstants.SIGNATURE_OFFSET;
            for ( int i = 0; i < 8; i++ )
                data[ index + i ] = 0;
            SMBUtil.writeInt4(this.signSequence, data, index);
            update(data, offset, length);
            System.arraycopy(digest(), 0, data, index, 8);
            if ( this.bypass ) {
                this.bypass = false;
                System.arraycopy("BSRSPYL ".getBytes(), 0, data, index, 8);
            }
        }
        catch ( Exception ex ) {
            log.error("Signature failed", ex);
        }
        finally {
            this.signSequence += 2;
        }
    }


    /**
     * Performs MAC signature verification. This calculates the signature
     * of the SMB and compares it to the signature field on the SMB itself.
     *
     * @param data
     *            The data.
     * @param offset
     *            The starting offset at which the SMB header begins.
     * @param length
     *            The length of the SMB data starting at offset.
     */
    boolean verify ( byte[] data, int offset, ServerMessageBlock response ) {
        update(this.macSigningKey, 0, this.macSigningKey.length);
        int index = offset;
        update(data, index, SmbConstants.SIGNATURE_OFFSET);
        index += SmbConstants.SIGNATURE_OFFSET;
        byte[] sequence = new byte[8];
        SMBUtil.writeInt4(response.signSeq, sequence, 0);
        update(sequence, 0, sequence.length);
        index += 8;
        if ( response.command == ServerMessageBlock.SMB_COM_READ_ANDX ) {
            /*
             * SmbComReadAndXResponse reads directly from the stream into separate byte[] b.
             */
            SmbComReadAndXResponse raxr = (SmbComReadAndXResponse) response;
            int length = response.length - raxr.dataLength;
            update(data, index, length - SmbConstants.SIGNATURE_OFFSET - 8);
            update(raxr.b, raxr.off, raxr.dataLength);
        }
        else {
            update(data, index, response.length - SmbConstants.SIGNATURE_OFFSET - 8);
        }
        byte[] signature = digest();
        for ( int i = 0; i < 8; i++ ) {
            if ( signature[ i ] != data[ offset + SmbConstants.SIGNATURE_OFFSET + i ] ) {
                if ( log.isDebugEnabled() ) {
                    log.debug("signature verification failure"); //$NON-NLS-1$
                    log.debug(Hexdump.toHexString(signature, 0, 8));
                    log.debug(Hexdump.toHexString(data, offset + SmbConstants.SIGNATURE_OFFSET, 8));
                }
                return response.verifyFailed = true;
            }
        }

        return response.verifyFailed = false;
    }


    @Override
    public String toString () {
        return "MacSigningKey=" + Hexdump.toHexString(this.macSigningKey, 0, this.macSigningKey.length);
    }


    void writeUTime ( Configuration cfg, long t, byte[] dst, int dstIndex ) {
        if ( t == 0L || t == 0xFFFFFFFFFFFFFFFFL ) {
            SMBUtil.writeInt4(0xFFFFFFFF, dst, dstIndex);
            return;
        }
    
        if ( cfg.getLocalTimezone().inDaylightTime(new Date()) ) {
            // in DST
            if ( cfg.getLocalTimezone().inDaylightTime(new Date(t)) ) {
                // t also in DST so no correction
            }
            else {
                // t not in DST so subtract 1 hour
                t -= 3600000;
            }
        }
        else {
            // not in DST
            if ( cfg.getLocalTimezone().inDaylightTime(new Date(t)) ) {
                // t is in DST so add 1 hour
                t += 3600000;
            }
            else {
                // t isn't in DST either
            }
        }
        SMBUtil.writeInt4((int) ( t / 1000L ), dst, dstIndex);
    }

}