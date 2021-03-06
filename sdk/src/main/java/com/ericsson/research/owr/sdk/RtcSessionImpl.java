/*
 * Copyright (c) 2015, Ericsson AB.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
package com.ericsson.research.owr.sdk;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import com.ericsson.research.owr.Candidate;
import com.ericsson.research.owr.DataChannel;
import com.ericsson.research.owr.DataSession;
import com.ericsson.research.owr.MediaSession;
import com.ericsson.research.owr.MediaSource;
import com.ericsson.research.owr.MediaType;
import com.ericsson.research.owr.Payload;
import com.ericsson.research.owr.RemoteMediaSource;
import com.ericsson.research.owr.Session;
import com.ericsson.research.owr.TransportAgent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

class RtcSessionImpl implements RtcSession {
    private static final String TAG = "RtcSessionImpl";

    private enum State {
        INIT, RECEIVED_OFFER, SETUP, AWAITING_ANSWER, ACTIVE, STOPPED
    }

    private static final String DEFAULT_HASH_FUNCTION = "sha-256";

    private TransportAgent mTransportAgent;

    private boolean mIsInitiator;
    private final String mSessionId;
    private final RtcConfig mConfig;
    private int mDataChannelLocalPort;

    private SessionDescription mRemoteDescription = null;
    private final Handler mMainHandler;

    private OnLocalCandidateListener mLocalCandidateListener = null;
    private List<StreamHandler> mStreamHandlers;
    private SetupCompleteCallback mSetupCompleteCallback;
    private List<RtcCandidate> mRemoteCandidateBuffer;
    private State mState;

    private static Random sRandom = new Random();

    RtcSessionImpl(RtcConfig config) {
        mSessionId = "" + (sRandom.nextInt() + new Date().getTime());
        mConfig = config;
        mState = State.INIT;
        mIsInitiator = true;
        mMainHandler = new Handler(Looper.getMainLooper());
        mDataChannelLocalPort = 5000;
    }

    @Override
    public synchronized void setOnLocalCandidateListener(final OnLocalCandidateListener listener) {
        mLocalCandidateListener = listener;
    }

    private void log(String msg) {
        String streams;
        if (mStreamHandlers == null) {
            streams = "[]";
        } else {
            streams = Arrays.toString(mStreamHandlers.toArray(new StreamHandler[mStreamHandlers.size()]));
        }
        Log.d(TAG, "[RtcSession" +
                " id=" + mSessionId +
                " initiator=" + isInitiator() +
                " state=" + mState.name() +
                " streams=" + streams +
                " candidates=" + (mRemoteCandidateBuffer == null ? 0 : mRemoteCandidateBuffer.size()) +
                " ] " + msg);
    }

    @Override
    public synchronized void setup(final StreamSet streamSet, final SetupCompleteCallback callback) {
        if (mState != State.INIT && mState != State.RECEIVED_OFFER) {
            throw new IllegalStateException("setup called at wrong state: " + mState.name());
        }
        if (streamSet == null) {
            throw new NullPointerException("streamSet may not be null");
        }
        if (callback == null) {
            throw new NullPointerException("callback may not be null");
        }
        log("setup called");
        mSetupCompleteCallback = callback;

        mTransportAgent = new TransportAgent(mIsInitiator);

        for (RtcConfig.HelperServer helperServer : mConfig.getHelperServers()) {
            mTransportAgent.addHelperServer(
                    helperServer.getType(),
                    helperServer.getAddress(),
                    helperServer.getPort(),
                    helperServer.getUsername(),
                    helperServer.getPassword()
            );
        }

        mStreamHandlers = new LinkedList<>();
        int index = 0;
        if (mIsInitiator) {
            // For outbound calls we initiate all streams without any remote description
            for (StreamSet.Stream stream : streamSet.getStreams()) {
                mStreamHandlers.add(createStreamHandler(index, null, stream));
                index++;
            }
        } else {
            for (Pair<StreamDescription, StreamSet.Stream> pair : Utils.resolveOfferedStreams(mRemoteDescription, streamSet.getStreams())) {
                mStreamHandlers.add(createStreamHandler(index, pair.first, pair.second));
                index++;
            }
        }
        for (StreamHandler handler : mStreamHandlers) {
            if (handler.getSession() != null) {
                mTransportAgent.addSession(handler.getSession());
            }
        }

        if (mRemoteDescription != null && mRemoteCandidateBuffer != null) {
            for (RtcCandidate candidate : mRemoteCandidateBuffer) {
                addRemoteCandidate(candidate);
            }
            mRemoteCandidateBuffer = null;
        }

        mState = State.SETUP;
        log("initial setup complete");

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                // we might be ready straight away if there are no active streams.
                // do this check asynchronously to keep code paths consistent
                maybeFinishSetup();
            }
        });
    }

    private synchronized void maybeFinishSetup() {
        final SetupCompleteCallback callback;
        final SessionDescription sessionDescription;

        if (mState != State.SETUP) {
            Log.w(TAG, "maybeFinishSetup called at wrong state: " + mState);
            return;
        }
        for (StreamHandler streamHandler : mStreamHandlers) {
            if (!streamHandler.isReady()) {
                return;
            }
        }
        log("setup complete");

        List<StreamDescription> streamDescriptions = new ArrayList<>(mStreamHandlers.size());

        for (StreamHandler streamHandler : mStreamHandlers) {
            streamDescriptions.add(streamHandler.finishLocalStreamDescription());
        }

        SessionDescription.Type type;
        if (mIsInitiator) {
            type = SessionDescription.Type.OFFER;
            mState = State.AWAITING_ANSWER;
        } else {
            type = SessionDescription.Type.ANSWER;
            mState = State.ACTIVE;
        }

        sessionDescription = new SessionDescriptionImpl(type, mSessionId, streamDescriptions);
        callback = mSetupCompleteCallback;
        mSetupCompleteCallback = null;

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSetupComplete(sessionDescription);
            }
        });
    }

    @Override
    public synchronized void setRemoteDescription(final SessionDescription remoteDescription) throws InvalidDescriptionException {
        if (mState != State.AWAITING_ANSWER && mState != State.INIT) {
            throw new IllegalStateException("setRemoteDescription called at wrong state: " + mState);
        }
        if (mRemoteDescription != null) {
            throw new IllegalStateException("remote description has already been set");
        }
        if (remoteDescription == null) {
            throw new NullPointerException("remote description should not be null");
        }
        if (mState == State.INIT) {
            mRemoteDescription = remoteDescription;
            mIsInitiator = false;
            mState = State.RECEIVED_OFFER;
            log("received offer");
            return;
        }
        mRemoteDescription = remoteDescription;
        log("received answer");

        List<StreamDescription> streamDescriptions = remoteDescription.getStreamDescriptions();
        int numStreamDescriptions = streamDescriptions.size();
        int numStreamHandlers = mStreamHandlers.size();

        if (numStreamDescriptions != numStreamHandlers) {
            throw new InvalidDescriptionException("session description has an invalid number of stream descriptions: " +
                    numStreamDescriptions + " != " + numStreamHandlers);
        }
        int size = Math.max(streamDescriptions.size(), mStreamHandlers.size());
        for (int i = 0; i < size; i++) {
            StreamDescription streamDescription = streamDescriptions.get(i);
            StreamHandler streamHandler = mStreamHandlers.get(i);
            if (streamDescription.getType() != streamHandler.getStream().getType()) {
                throw new InvalidDescriptionException("stream description types do not match: " +
                        streamDescription.getType() + " != " + streamHandler.getStream().getType());
            }
            streamHandler.provideAnswer(streamDescription);
        }

        if (mRemoteCandidateBuffer != null) {
            for (RtcCandidate candidate : mRemoteCandidateBuffer) {
                addRemoteCandidate(candidate);
            }
            mRemoteCandidateBuffer = null;
        }
    }

    @Override
    public synchronized void addRemoteCandidate(final RtcCandidate candidate) {
        if (mState == State.STOPPED) {
            return;
        } else if (mRemoteDescription == null || mState == State.RECEIVED_OFFER) {
            if (mRemoteCandidateBuffer == null) {
                mRemoteCandidateBuffer = new LinkedList<>();
            }
            mRemoteCandidateBuffer.add(candidate);
            Log.d(TAG, "[RtcSession] buffering candidate for stream " + candidate.getStreamIndex());
            return;
        }

        StreamHandler streamHandler = null;
        int index = candidate.getStreamIndex();
//        String id = candidate.getStreamId();

        if (index < 0) {
            // TODO: use id?
/*            if (id != null) {
                for (StreamHandler handler : mStreamHandlers) {
                    if (id.equals(handler.getStream().getId())) {
                        streamHandler = handler;
                        break;
                    }
                }
            }*/
        } else if (index < mStreamHandlers.size()) {
            streamHandler = mStreamHandlers.get(index);
        }

        if (streamHandler != null) {
            Log.d(TAG, "[RtcSession] got remote candidate for " + streamHandler);
            streamHandler.onRemoteCandidate(candidate);
        }
    }

    @Override
    public void stop() {
        mState = State.STOPPED;

        if (mStreamHandlers != null) {
            for (StreamHandler streamHandler : mStreamHandlers) {
                streamHandler.stop();
            }
        }

        mLocalCandidateListener = null;
        mSetupCompleteCallback = null;
        mRemoteCandidateBuffer = null;
        mRemoteDescription = null;
        mTransportAgent = null;
        mStreamHandlers = null;
    }

    @Override
    public String dumpPipelineGraph() {
        if (mTransportAgent != null) {
            return mTransportAgent.getDotData();
        }
        return null;
    }

    public boolean isInitiator() {
        return mIsInitiator;
    }

    public RtcConfig getConfig() {
        return mConfig;
    }

    private StreamHandler createStreamHandler(int index, StreamDescription streamDescription, StreamSet.Stream stream) {
        if (stream == null) {
            if (streamDescription.getType() == StreamType.DATA) {
                return new DataStreamHandler(index, streamDescription);
            } else {
                return new MediaStreamHandler(index, streamDescription);
            }
        } else if (stream.getType() == StreamType.DATA) {
            return new DataStreamHandler(index, streamDescription, (StreamSet.DataStream) stream);
        } else {
            return new MediaStreamHandler(index, streamDescription, (StreamSet.MediaStream) stream);
        }
    }

    /*
##     ##    ###    ##    ## ########  ##       ######## ########   ######
##     ##   ## ##   ###   ## ##     ## ##       ##       ##     ## ##    ##
##     ##  ##   ##  ####  ## ##     ## ##       ##       ##     ## ##
######### ##     ## ## ## ## ##     ## ##       ######   ########   ######
##     ## ######### ##  #### ##     ## ##       ##       ##   ##         ##
##     ## ##     ## ##   ### ##     ## ##       ##       ##    ##  ##    ##
##     ## ##     ## ##    ## ########  ######## ######## ##     ##  ######
     */

    // TODO: verify peer cert
    private abstract class StreamHandler implements Session.DtlsCertificateChangeListener, Session.OnNewCandidateListener {
        private final StreamSet.Stream mStream;
        private final MutableStreamDescription mLocalStreamDescription;
        private final int mIndex;
        private StreamDescription mRemoteStreamDescription;
        private Session mSession;
        private boolean mHaveCandidate = false;
        private boolean mHaveFingerprint = false;
        private boolean mLocalDescriptionCreated = false;

        StreamHandler(int index, StreamDescription streamDescription, StreamSet.Stream stream, Session session) {
            mLocalStreamDescription = new MutableStreamDescription();
            mRemoteStreamDescription = streamDescription;
            mIndex = index;
            mStream = stream;
            mSession = session;

            if (stream == null) { // Inactive stream
                mLocalStreamDescription.setMode(StreamMode.INACTIVE);
                mLocalStreamDescription.setType(streamDescription.getType());
                return;
            }

            if (streamDescription != null) {
                for (RtcCandidate rtcCandidate : getRemoteStreamDescription().getCandidates()) {
                    Candidate candidate = Utils.transformCandidate(rtcCandidate);
                    candidate.setUfrag(streamDescription.getUfrag());
                    candidate.setPassword(streamDescription.getPassword());
                    mSession.addRemoteCandidate(candidate);
                }
            }

            mSession.addDtlsCertificateChangeListener(this);
            mSession.addOnNewCandidateListener(this);

            mLocalStreamDescription.setType(getStream().getType());

            String fingerprintHashFunction;
            String dtlsSetup;

            if (isInitiator()) {
                fingerprintHashFunction = DEFAULT_HASH_FUNCTION;
                dtlsSetup = "actpass";
            } else {
                fingerprintHashFunction = getRemoteStreamDescription().getFingerprintHashFunction();
                dtlsSetup = "active";
            }

            getLocalStreamDescription().setFingerprintHashFunction(fingerprintHashFunction);
            getLocalStreamDescription().setDtlsSetup(dtlsSetup);
        }

        // Inactive stream
        StreamHandler(int index, StreamDescription streamDescription) {
            this(index, streamDescription, null, null);
        }

        public boolean isDtlsClient() {
            return !isInitiator();
        }

        public int getIndex() {
            return mIndex;
        }

        protected Session getSession() {
            return mSession;
        }

        protected StreamSet.Stream getStream() {
            return mStream;
        }

        protected MutableStreamDescription getLocalStreamDescription() {
            return mLocalStreamDescription;
        }

        protected StreamDescription getRemoteStreamDescription() {
            return mRemoteStreamDescription;
        }

        public StreamDescription finishLocalStreamDescription() {
            mLocalDescriptionCreated = true;
            return getLocalStreamDescription();
        }

        public boolean isReady() {
            boolean isInactive = getLocalStreamDescription().getMode() == StreamMode.INACTIVE;
            return mHaveCandidate && mHaveFingerprint || isInactive;
        }

        public void provideAnswer(StreamDescription remoteStreamDescription) {
            if (!isInitiator()) {
                throw new IllegalStateException("remote description set for outbound call");
            }
            mRemoteStreamDescription = remoteStreamDescription;

            for (RtcCandidate rtcCandidate : getRemoteStreamDescription().getCandidates()) {
                Candidate candidate = Utils.transformCandidate(rtcCandidate);
                candidate.setUfrag(remoteStreamDescription.getUfrag());
                candidate.setPassword(remoteStreamDescription.getPassword());
                getSession().addRemoteCandidate(candidate);
            }
        }

        public void stop() {
            if (getSession() != null) {
                getSession().removeDtlsCertificateChangeListener(this);
                getSession().removeOnNewCandidateListener(this);
            }
            mSession = null;
            mRemoteStreamDescription = null;
        }

        @Override
        public synchronized void onDtlsCertificateChanged(String pem) {
            String fingerprintHashFunction = getLocalStreamDescription().getFingerprintHashFunction();
            String fingerprint = Utils.fingerprintFromPem(pem, fingerprintHashFunction);
            getLocalStreamDescription().setFingerprint(fingerprint);
            mHaveFingerprint = true;
            if (isReady()) {
                maybeFinishSetup();
            }
        }

        @Override
        public synchronized void onNewCandidate(Candidate candidate) {
            if (!mHaveCandidate) {
                getLocalStreamDescription().setUfrag(candidate.getUfrag());
                getLocalStreamDescription().setPassword(candidate.getPassword());
            }

            final PlainRtcCandidate rtcCandidate = PlainRtcCandidate.fromOwrCandidate(candidate);
            if (mLocalDescriptionCreated) {
                Log.d(TAG, "[RtcSession] got local candidate for " + this);
                rtcCandidate.setStreamIndex(getIndex());
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mLocalCandidateListener != null) {
                            mLocalCandidateListener.onLocalCandidate(rtcCandidate);
                        }
                    }
                });
            } else {
                getLocalStreamDescription().addCandidate(rtcCandidate);
            }


            if (!mHaveCandidate) {
                mHaveCandidate = true;
                if (isReady()) {
                    maybeFinishSetup();
                }
            }
        }

        public void onRemoteCandidate(RtcCandidate rtcCandidate) {
            if (getStream() == null) {
                return;
            }
            boolean isRtcp = rtcCandidate.getComponentType() == RtcCandidate.ComponentType.RTCP;
            if (getLocalStreamDescription().isRtcpMux() && isRtcp) {
                return;
            }
            Candidate candidate = Utils.transformCandidate(rtcCandidate);
            candidate.setUfrag(getRemoteStreamDescription().getUfrag());
            candidate.setPassword(getRemoteStreamDescription().getPassword());
            getSession().addRemoteCandidate(candidate);
        }

        @Override
        public String toString() {
            if (getLocalStreamDescription() == null) {
                return "Stream{}";
            }
            return "Stream{" +
                    getLocalStreamDescription().getType().toString().charAt(0) + getIndex() + "," +
                    getLocalStreamDescription().getMode() + "}";
        }
    }

    private class MediaStreamHandler extends StreamHandler implements MediaSession.OnIncomingSourceListener, MediaSession.CnameChangeListener, StreamSet.MediaSourceDelegate, MediaSession.SendSsrcChangeListener {
        private boolean mHaveCname = false;
        private boolean mHaveSsrc = false;

        MediaStreamHandler(int index, StreamDescription streamDescription, StreamSet.MediaStream mediaStream) {
            super(index, streamDescription, mediaStream, new MediaSession(!isInitiator()));
            getMediaSession().addCnameChangeListener(this);
            getMediaSession().addSendSsrcChangeListener(this);
            getMediaSession().addOnIncomingSourceListener(this);
            getMediaStream().setMediaSourceDelegate(this);

            String mediaStreamId = getMediaStream().getId();
            if (mediaStreamId == null) {
                getLocalStreamDescription().setMediaStreamId(Utils.randomString(27));
            } else {
                getLocalStreamDescription().setMediaStreamId(mediaStreamId);
            }
            getLocalStreamDescription().setMediaStreamTrackId(Utils.randomString(27));

            boolean rtcpMux;
            StreamMode mode;
            boolean wantSend = getMediaStream().wantSend();
            boolean wantReceive = getMediaStream().wantReceive();

            if (isInitiator()) {
                mode = StreamMode.get(wantSend, wantReceive);
                rtcpMux = true;
            } else {
                mode = getRemoteStreamDescription().getMode().reverse(wantSend, wantReceive);
                rtcpMux = getRemoteStreamDescription().isRtcpMux();
                getStream().setStreamMode(mode);
            }

            if (mode == StreamMode.INACTIVE) {
                if (isInitiator()) {
                    getStream().setStreamMode(mode);
                }
                return;
            }

            getLocalStreamDescription().setMode(mode);
            getMediaSession().setRtcpMux(rtcpMux);
            getLocalStreamDescription().setRtcpMux(rtcpMux);

            List<RtcPayload> payloads;
            if (getMediaStream().getMediaType() == MediaType.VIDEO) {
                payloads = getConfig().getDefaultVideoPayloads();
            } else {
                payloads = getConfig().getDefaultAudioPayloads();
            }
            if (!isInitiator()) {
                payloads = Utils.intersectPayloads(getRemoteStreamDescription().getPayloads(), payloads);
                payloads = Utils.selectPreferredPayload(payloads);
            }
            for (RtcPayload payload : payloads) {
                getLocalStreamDescription().addPayload(payload);
            }
            List<Payload> transformedPayloads = Utils.transformPayloads(payloads, getMediaStream().getMediaType());
            if (transformedPayloads.isEmpty()) {
                Log.w(TAG, "no suitable payload found for stream: " + getMediaStream().getId());
                getStream().setStreamMode(StreamMode.INACTIVE);
                // TODO: stop stream
                return;
            }

            if (mode.wantReceive()) {
                for (Payload payload : transformedPayloads) {
                    getMediaSession().addReceivePayload(payload);
                }
            }
            if (!isInitiator() && mode.wantSend()) {
                getMediaSession().setSendPayload(transformedPayloads.get(0));
            }
        }

        // inactive stream
        MediaStreamHandler(int index, StreamDescription streamDescription) {
            super(index, streamDescription);
        }

        public MediaSession getMediaSession() {
            return (MediaSession) getSession();
        }

        public StreamSet.MediaStream getMediaStream() {
            return (StreamSet.MediaStream) getStream();
        }

        @Override
        public void provideAnswer(StreamDescription streamDescription) {
            super.provideAnswer(streamDescription);
            StreamMode mode = getRemoteStreamDescription().getMode().reverse(
                    getMediaStream().wantSend(), getMediaStream().wantReceive()
            );
            getLocalStreamDescription().setMode(mode);
            getStream().setStreamMode(mode);
            if (mode == StreamMode.INACTIVE) {
                return;
            }
            if (!getRemoteStreamDescription().isRtcpMux()) {
                getMediaSession().setRtcpMux(false);
            }

            if (mode.wantSend()) {
                List<Payload> transformedPayloads = Utils.transformPayloads(
                        getRemoteStreamDescription().getPayloads(), getMediaStream().getMediaType());
                if (transformedPayloads.isEmpty()) {
                    Log.w(TAG, "no suitable payload found for stream: " + getMediaStream().getId());
                    getStream().setStreamMode(StreamMode.INACTIVE);
                    // TODO: stop stream
                    return;
                }
                getMediaSession().setSendPayload(transformedPayloads.get(0));
            }
        }

        @Override
        public boolean isReady() {
            boolean isInactive = getLocalStreamDescription().getMode() == StreamMode.INACTIVE;
            return super.isReady() && mHaveSsrc && mHaveCname || isInactive;
        }

        @Override
        public void stop() {
            if (getMediaSession() != null) {
                getMediaSession().removeCnameChangeListener(this);
                getMediaSession().removeSendSsrcChangeListener(this);
                getMediaSession().removeOnIncomingSourceListener(this);
                getMediaSession().setSendSource(null);
            }
            if (getMediaStream() != null) {
                getMediaStream().onRemoteMediaSource(null);
                getMediaStream().setMediaSourceDelegate(null);
            }
            super.stop();
        }

        @Override
        public void onCnameChanged(String cname) {
            getLocalStreamDescription().setCname(cname);
            mHaveCname = true;

            if (isReady()) {
                maybeFinishSetup();
            }
        }

        @Override
        public void onSendSsrcChanged(final int ssrc) {
            long unsignedSsrc = ssrc & 0xFFFFFFFFL;
            if (unsignedSsrc > 0) {
                getLocalStreamDescription().addSsrc(unsignedSsrc);
                mHaveSsrc = true;

                if (isReady()) {
                    maybeFinishSetup();
                }
            }
        }

        @Override
        public void onIncomingSource(RemoteMediaSource remoteMediaSource) {
            if (mState != State.STOPPED) {
                getMediaStream().onRemoteMediaSource(remoteMediaSource);
            }
        }

        @Override
        public void setMediaSource(MediaSource mediaSource) {
            if (mState != State.STOPPED) {
                getMediaSession().setSendSource(mediaSource);
            }
        }
    }

    private class DataStreamHandler extends StreamHandler implements DataSession.OnDataChannelRequestedListener, StreamSet.DataChannelDelegate {
        private final List<DataChannel> mDataChannels = new ArrayList<>();

        private Session.DtlsKeyChangeListener mDtlsKeyChangeListener = new Session.DtlsKeyChangeListener() {
            @Override
            public void onDtlsKeyChanged(final String s) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        getStream().setStreamMode(StreamMode.SEND_RECEIVE);
                    }
                });
                if (getSession() != null) {
                    getSession().removeDtlsKeyChangeListener(this);
                }
                mDtlsKeyChangeListener = null;
            }
        };

        public DataStreamHandler(int index, StreamDescription streamDescription, StreamSet.DataStream dataStream) {
            super(index, streamDescription, dataStream, new DataSession(!isInitiator()));
            getDataSession().addOnDataChannelRequestedListener(this);
            getDataSession().addDtlsKeyChangeListener(mDtlsKeyChangeListener);
            getDataStream().setDataChannelDelegate(this);

            StreamMode mode;
            String appLabel;
            int localPort = mDataChannelLocalPort++;
            int streamCount;

            if (isInitiator()) {
                mode = StreamMode.SEND_RECEIVE;
                appLabel = "webrtc-datachannel";
                streamCount = 1024;
            } else {
                if (getRemoteStreamDescription().getMode() != StreamMode.INACTIVE) {
                    mode = StreamMode.SEND_RECEIVE;
                } else {
                    mode = StreamMode.INACTIVE;
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            getStream().setStreamMode(StreamMode.INACTIVE);
                        }
                    });
                }
                appLabel = getRemoteStreamDescription().getAppLabel();
                int remotePort = getRemoteStreamDescription().getSctpPort();

                streamCount = getRemoteStreamDescription().getSctpStreamCount();

                getDataSession().setSctpRemotePort(remotePort);
            }

            getLocalStreamDescription().setMode(mode);
            getLocalStreamDescription().setAppLabel(appLabel);
            getLocalStreamDescription().setSctpPort(localPort);
            getLocalStreamDescription().setSctpStreamCount(streamCount);
            getDataSession().setSctpLocalPort(localPort);
        }

        public DataStreamHandler(int index, StreamDescription streamDescription) {
            super(index, streamDescription);
        }

        public DataSession getDataSession() {
            return (DataSession) getSession();
        }

        public StreamSet.DataStream getDataStream() {
            return (StreamSet.DataStream) getStream();
        }

        @Override
        public void provideAnswer(StreamDescription streamDescription) {
            super.provideAnswer(streamDescription);
            StreamMode mode;
            if (getRemoteStreamDescription().getMode() != StreamMode.SEND_RECEIVE) {
                mode = StreamMode.INACTIVE;
            } else {
                mode = StreamMode.SEND_RECEIVE;
            }
            getLocalStreamDescription().setMode(mode);
            getStream().setStreamMode(mode);
            if (mode == StreamMode.INACTIVE) {
                return;
            }
            int remotePort = getRemoteStreamDescription().getSctpPort();
            int streamCount = getRemoteStreamDescription().getSctpStreamCount();

            if (streamCount > 0) {
                getLocalStreamDescription().setSctpStreamCount(streamCount);
            }

            getDataSession().setSctpRemotePort(remotePort);
        }

        @Override
        public void stop() {
            if (getDataSession() != null) {
                getDataSession().removeOnDataChannelRequestedListener(this);
            }
            if (getDataStream() != null) {
                getDataStream().setDataChannelDelegate(null);
            }
            for (DataChannel dataChannel : mDataChannels) {
                dataChannel.close();
            }
            mDataChannels.clear();
            super.stop();
        }

        @Override
        public void onDataChannelRequested(boolean ordered, int max_packet_life_time, int max_retransmits, String protocol, boolean negotiated, int id, String label) {
            Log.d(TAG, "DATACHANNEL requested:" +
                    " ordered=" + ordered +
                    " max_packet_life_time=" + max_packet_life_time +
                    " max_retransmits=" + max_retransmits +
                    " protocol=" + protocol +
                    " negotiated=" + negotiated +
                    " id=" + id +
                    " label=" + label);

            final DataChannel dataChannel = new DataChannel(ordered, max_packet_life_time, max_retransmits, protocol, negotiated, (short) id, label);

            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean keep = false;

                    if (getDataStream() != null) {
                        keep = getDataStream().onDataChannelReceived(dataChannel);
                    }

                    if (keep) {
                        Log.d(TAG, "adding datachannel to session: " + dataChannel);
                        getDataSession().addDataChannel(dataChannel);
                    }
                }
            });
        }

        @Override
        public void addDataChannel(final DataChannel dataChannel) {
            if (getDataSession() != null) {
                getDataSession().addDataChannel(dataChannel);
            }
        }
    }
}
