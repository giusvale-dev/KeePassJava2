/*
 * Copyright 2015 Jo Rabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.linguafranca.pwdb.kdbx;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.linguafranca.pwdb.security.Encryption;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.security.DigestInputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Class has a static method to load a key from an {@link InputStream}
 */
@SuppressWarnings("WeakerAccess")
public class KdbxKeyFile {

    private static final XPath xpath = XPathFactory.newInstance().newXPath();
    private static final int BUFFER_SIZE = 65;
    private static final int KEY_LEN_32 = 32;
    private static final int KEY_LEN_64 = 64;

    /**
     * Load a key from an InputStream
     * <p>
     * The InputStream can represent ... TODO write about the formats
     *
     * @param inputStream the input stream holding the key, caller should close
     * @return the key
     */
    public static byte[] load(InputStream inputStream) {
        // wrap the stream to get its digest (in case we need it)
        DigestInputStream digestInputStream = new DigestInputStream(inputStream,
                Encryption.getSha256MessageDigestInstance());
        // wrap the stream, so we can test reading from it but then push back to get original stream
        PushbackInputStream pis = new PushbackInputStream(digestInputStream, BUFFER_SIZE);
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead = pis.read(buffer);

            // if length 32 assume binary key file
            if (bytesRead == KEY_LEN_32) {
                return buffer;
            }

            // if length 64 may be hex encoded key file
            if (bytesRead == KEY_LEN_64) {
                try {
                    return Hex.decodeHex(ByteBuffer.wrap(buffer).asCharBuffer().array()); // (avoid creating a String)
                } catch (DecoderException ignored) {
                    // fall through it may be an XML file or just a file whose digest we want
                }
            }
            // restore stream
            pis.unread(buffer);

            // if length not 32 or 64 either an XML key file or just a file to get digest
            try {
                // see if it's an XML key file
                return tryComputeXmlKeyFile(pis);
            } catch (HashMismatchException e) {
                throw new IllegalArgumentException("Invalid key in signature file");
            } catch (Exception ignored) {
                // fall through to get file digest
            }

            // is not a valid xml file, so read the remainder of file
            byte[] sink = new byte[1024];
            // read file to get its digest
            //noinspection StatementWithEmptyBody
            while (digestInputStream.read(sink) > 0) { /* nothing */ }
            return digestInputStream.getMessageDigest().digest();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Read the InputStream (kdbx xml keyfile) and compute the hash (SHA-256) to build a key
     *
     * @param is The KeyFile as an InputStream, must return with stream open on error
     * @return the computed byte array (keyFile) to compute the MasterKey
     */
    private static byte[] tryComputeXmlKeyFile(InputStream is) throws HashMismatchException {
        // DocumentBuilder closes input stream so wrap inputStream to inhibit this in case of failure
        InputStream unCloseable = new FilterInputStream(is) {
            @Override
            public void close() { /* nothing */ }
        };
        try {
            DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = documentBuilder.parse(unCloseable);
            // get the key
            String data = (String) xpath.evaluate("//KeyFile/Key/Data/text()", doc, XPathConstants.STRING);
            if (data == null) {
                throw new IllegalArgumentException("Key file does not contain a key");
            }
            // get the file version
            String version = (String) xpath.evaluate("//KeyFile/Meta/Version/text()", doc, XPathConstants.STRING);
            // if not 2.0 then key is base64 encoded
            if (Objects.isNull(version) || !version.equals("2.0")) {
                return Base64.decodeBase64(data);
            }

            // key data may contain white space
            byte[] decodedData = Hex.decodeHex(data.replaceAll("\\s", ""));
            byte[] decodedDataHash = Encryption.getSha256MessageDigestInstance().digest(decodedData);

            // hash used to verify the data
            String hashToCheck = (String) xpath.evaluate("//KeyFile/Key/Data/@Hash", doc, XPathConstants.STRING);
            byte[] decodedHashToCheck = Hex.decodeHex(hashToCheck);

            // hashToCheck is a truncated version of the actual hash
            if (!Arrays.equals(Arrays.copyOf(decodedDataHash, decodedHashToCheck.length), decodedHashToCheck)) {
                throw new HashMismatchException();
            }
            return decodedData;
        } catch (IOException | SAXException | ParserConfigurationException | XPathExpressionException |
                 DecoderException e) {
            throw new IllegalArgumentException("An error occurred during XML parsing: " + e.getMessage());
        }
    }

    private static class HashMismatchException extends Exception {
    }
}
