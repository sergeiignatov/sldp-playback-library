package com.softvelum.sldp;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpParser {

    enum HttpParserState {
        INTERLEAVED,
        STATUS_LINE,
        HDR_LINE,
        BODY
    }

    private static final Pattern STATUS_LINE_PATTERN = Pattern.compile("HTTP\\/1.\\d\\s+(\\d\\d\\d)\\s+(.+)");
    private static final Pattern HDR_LINE_PATTERN = Pattern.compile("(\\S+):\\s?+(.*)");

    private int mStatusCode;
    private String mStatusText;
    private HttpParserState mState = HttpParserState.INTERLEAVED;
    private int mContentLength;
    private boolean mComplete;
    private final HashMap<String, String> mHdr = new HashMap<>();
    private String mContentType;
    private int mIcyMetaInt;

    public boolean getComplete() {
        return mComplete;
    }

    public int getStatusCode() {
        return mStatusCode;
    }

    public String getStatusText() {
        return mStatusText;
    }

    String getHeader(String name) {
        return mHdr.get(name.toUpperCase());
    }

    public int getMetadataInterval() {
        return mIcyMetaInt;
    }

    public String getContentType() {
        return mContentType;
    }

    private boolean parseStatusLine(String s) {

        Matcher m = STATUS_LINE_PATTERN.matcher(s);
        if (!m.find()) {
            return false;
        }

        mStatusCode = Integer.parseInt(m.group(1));
        mStatusText = m.group(2);

        return true;
    }

    private boolean parseHdrLine(String s) {

        Matcher m = HDR_LINE_PATTERN.matcher(s);
        if (!m.find()) {
            return false;
        }

        String name = m.group(1).trim();
        String value = m.group(2).trim();

        if (name.equalsIgnoreCase("Content-length")) {
            mContentLength = Integer.parseInt(value);

        } else if (name.equalsIgnoreCase("WWW-Authenticate")) {
            // <scheme> <key>="<value>",<key>="<value>",...,<key>="<value>"
            int pos = value.indexOf(" ");
            if (pos != -1) {
                String authScheme = value.substring(0, pos);
                String authParams = value.substring(pos + 1).trim();
                if (authScheme.equalsIgnoreCase("Digest")) {
                    parseAuth("WWW-Authenticate-Digest", authParams);
                } else if (authScheme.equalsIgnoreCase("Basic")) {
                    parseAuth("WWW-Authenticate-Basic", authParams);
                } else {
                    return true;
                }
            }

        } else if (name.equalsIgnoreCase("content-type")) {
            mContentType = value;

        } else if (name.equalsIgnoreCase("icy-metaint")) {
            try {
                mIcyMetaInt = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        mHdr.put(name.toUpperCase(), value);

        return true;
    }

    private void parseAuth(String key, String value) {
        String[] paramList = value.split(",");
        for (String s : paramList) {
            int pos = s.indexOf("=");
            if (pos == -1) {
                continue;
            }

            String paramName = s.substring(0, pos).trim();
            if (paramName.isEmpty()) {
                continue;
            }
            paramName = key + "-" + paramName;
            paramName = paramName.toUpperCase();

            String paramValue = s.substring(pos + 1).replace("\"", "").trim();
            mHdr.put(paramName.toUpperCase(), paramValue);
        }
    }

    private int getLine(byte[] buffer, int offset, int len, StringBuilder line) {
        boolean cr = false;

        for (int i = offset; i < len; i++) {

            if (cr && buffer[i] == '\n') {
                return line.length() + 2;
            }

            cr = false;
            if (buffer[i] == '\r') {
                cr = true;
            } else {
                line.append((char) buffer[i]);
            }
        }
        return -1;
    }

    private boolean isHttp(byte[] buffer, int offset) {
        return buffer[offset] == 'H'
                && buffer[offset + 1] == 'T'
                && buffer[offset + 2] == 'T'
                && buffer[offset + 3] == 'P';
    }

    private boolean isShoutCast(byte[] buffer, int offset) {
        return buffer[offset] == 'I'
                && buffer[offset + 1] == 'C'
                && buffer[offset + 2] == 'Y';
    }

    public int parse(byte[] buffer, int len) {
        int offset = 0;

        while (len > 0) {

            int parsed;

            switch (mState) {
                case INTERLEAVED:
                    mStatusCode = -1;
                    mStatusText = "";
                    mHdr.clear();
                    mContentLength = 0;

                    if (len < 4) {
                        return 0;
                    } else if (isHttp(buffer, offset) || isShoutCast(buffer, offset)) {
                        mState = HttpParserState.STATUS_LINE;
                    } else {
                        return offset;
                    }
                    break;

                case STATUS_LINE:
                    StringBuilder status_line = new StringBuilder();
                    parsed = getLine(buffer, offset, len, status_line);
                    if (-1 == parsed) {
                        // no crlf found
                        return offset;
                    }
                    offset += parsed;

                    if (!parseStatusLine(status_line.toString())) {
                        mState = HttpParserState.INTERLEAVED;
                        return -1;
                    }
                    mState = HttpParserState.HDR_LINE;
                    break;

                case HDR_LINE:
                    StringBuilder hdrLine = new StringBuilder();
                    parsed = getLine(buffer, offset, len, hdrLine);
                    if (parsed == -1) {
                        // no crlf found
                        return offset;
                    }
                    offset += parsed;

                    if (hdrLine.length() > 0) {
                        if (!parseHdrLine(hdrLine.toString())) {
                            mState = HttpParserState.INTERLEAVED;
                            return -1;
                        }
                    } else {
                        // header complete
                        if (mContentLength > 0) {
                            mState = HttpParserState.BODY;
                        } else {
                            mComplete = true;
                            mState = HttpParserState.INTERLEAVED;
                            return offset;
                        }
                    }
                    break;

                case BODY:
                    if (len < mContentLength) {
                        // wait for the whole body
                        return offset;
                    }

                    // TBD process body

                    offset += mContentLength;
                    mComplete = true;
                    mState = HttpParserState.INTERLEAVED;
                    return offset;

                default:
                    break;
            }

        }
        return 0;
    }

}
