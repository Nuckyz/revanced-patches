package app.revanced.extension.spotify.spoof;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.spotify.clienttoken.data.v0.ConnectivitySdkData;
import app.revanced.extension.spotify.clienttoken.data.v0.NativeIOSData;
import app.revanced.extension.spotify.clienttoken.data.v0.PlatformSpecificData;
import app.revanced.extension.spotify.clienttoken.http.v0.*;
import app.revanced.extension.spotify.shared.Requester;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SpoofClientTokenPatch {

    private static final String CLIENT_VERSION = "iphone-9.0.50.511.gc711db6";
    private static final String CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3";
    private static final String HW_MACHINE = "iPhone16,1";
    private static final String SYSTEM_VERSION = "17.7.2";

    private static final String CLIENT_TOKEN_ENDPOINT = "https://clienttoken.spotify.com/v1/clienttoken";
    private static final String IOS_USER_AGENT = "Spotify/9.0.50 iOS/17.7.2";

    private static ClientTokenRequest buildClientTokenRequest(String deviceId) {
        return ClientTokenRequest.newBuilder()
                .setRequestType(ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST)
                .setClientData(ClientDataRequest.newBuilder()
                        .setClientVersion(CLIENT_VERSION)
                        .setClientId(CLIENT_ID)
                        .setConnectivitySdkData(ConnectivitySdkData.newBuilder()
                                .setDeviceId(deviceId)
                                .setPlatformSpecificData(PlatformSpecificData.newBuilder()
                                        .setIos(NativeIOSData.newBuilder()
                                                .setHwMachine(HW_MACHINE)
                                                .setSystemVersion(SYSTEM_VERSION)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static ClientTokenRequest buildChallengeAnswerRequest(String suffix, String state) {
        return ClientTokenRequest.newBuilder()
                .setRequestType(ClientTokenRequestType.REQUEST_CHALLENGE_ANSWERS_REQUEST)
                .setChallengeAnswers(ChallengeAnswersRequest.newBuilder()
                        .addAnswers(ChallengeAnswer.newBuilder()
                                .setChallengeType(ChallengeType.CHALLENGE_HASH_CASH)
                                .setHashCash(HashCashAnswer.newBuilder()
                                        .setSuffix(suffix)
                                        .build())
                                .build())
                        .setState(state)
                        .build())
                .build();
    }

    @Nullable
    public static byte[] solveHashCash(byte[] ctx, byte[] prefix, int length) throws NoSuchAlgorithmException {
        final long TIMEOUT_SECONDS = 5;
        final long startTimeNanos = System.nanoTime();

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] md = sha1.digest(ctx);
        long target = ByteBuffer.wrap(md, 12, 8).getLong();
        long counter = 0;

        while (true) {
            long elapsedNanos = System.nanoTime() - startTimeNanos;
            if (TimeUnit.NANOSECONDS.toSeconds(elapsedNanos) >= TIMEOUT_SECONDS) {
                return null;
            }

            // Construct the 16-byte suffix from the two long values
            ByteBuffer suffixBuffer = ByteBuffer.allocate(16);
            suffixBuffer.putLong(target + counter);
            suffixBuffer.putLong(counter);
            byte[] suffix = suffixBuffer.array();

            sha1.reset();
            sha1.update(prefix);
            sha1.update(suffix);
            byte[] finalHash = sha1.digest();

            long valueToCheck = ByteBuffer.wrap(finalHash, 12, 8).getLong();
            if (Long.numberOfTrailingZeros(valueToCheck) >= length) {
                return suffix;
            }

            counter++;
        }
    }

    @NonNull
    private static byte[] hexStringToByteArray(@NonNull String hexString) {
        int len = hexString.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have an even number of characters.");
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }

        return data;
    }

    @NonNull
    private static String bytesToHexString(@NonNull byte[] bytes) {
        StringBuilder hexBuilder = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            // Use "%02X" to format each byte as a two-character, zero-padded, UPPERCASE hex value.
            hexBuilder.append(String.format("%02X", b));
        }

        return hexBuilder.toString();
    }

    private static ClientTokenResponse requestClientToken() throws IOException {
        HttpURLConnection connection = Requester.getProtobufRequestConnection(CLIENT_TOKEN_ENDPOINT, "POST");
        connection.setRequestProperty("User-Agent", IOS_USER_AGENT);

        byte[] clientTokenRequestBody = buildClientTokenRequest("162891e9e52c030faf916f4df8fb4475").toByteArray();
        connection.setRequestProperty("Content-Length", Integer.toString(clientTokenRequestBody.length));
        connection.getOutputStream().write(clientTokenRequestBody);

        int responseCode = connection.getResponseCode();
        Logger.printInfo(() -> "Status code: " + responseCode);

        ClientTokenResponse response = ClientTokenResponse.parseFrom(connection.getInputStream());
        connection.disconnect();

        return response;
    }

    private static ClientTokenResponse requestChallengeAnswer(String suffix, String state) throws IOException {
        HttpURLConnection connection = Requester.getProtobufRequestConnection(CLIENT_TOKEN_ENDPOINT, "POST");
        connection.setRequestProperty("User-Agent", IOS_USER_AGENT);

        byte[] challenAnswerRequestBody = buildChallengeAnswerRequest(suffix, state).toByteArray();
        connection.setRequestProperty("Content-Length", Integer.toString(challenAnswerRequestBody.length));
        connection.getOutputStream().write(challenAnswerRequestBody);

        int responseCode = connection.getResponseCode();
        Logger.printInfo(() -> "Status code: " + responseCode);

        ClientTokenResponse response = ClientTokenResponse.parseFrom(connection.getInputStream());
        connection.disconnect();

        return response;
    }

    @Nullable
    private static String solveChallenge(@NonNull ChallengesResponse challenges) throws NoSuchAlgorithmException {
        if (challenges.getChallengesCount() == 0) {
            return null;
        }

        Challenge challenge = challenges.getChallenges(0);
        if (challenge.getType() != ChallengeType.CHALLENGE_HASH_CASH) {
            return null;
        }

        HashCashParameters hash_cash_parameters = challenge.getEvaluateHashcashParameters();

        byte[] ctx = new byte[0];
        byte[] prefix = hexStringToByteArray(hash_cash_parameters.getPrefix());
        int length = hash_cash_parameters.getLength();

        byte[] suffix_bytes = solveHashCash(ctx, prefix, length);
        if (suffix_bytes == null) {
            return null;
        }

        return bytesToHexString(suffix_bytes);
    }

    public static void generateToken() {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                ClientTokenResponse clientTokenResponse = requestClientToken();
                if (!clientTokenResponse.hasChallenges()) {
                    return;
                }

                ChallengesResponse challenges = clientTokenResponse.getChallenges();

                String state = challenges.getState();
                String suffix = solveChallenge(clientTokenResponse.getChallenges());

                ClientTokenResponse answerChallengeResponse = requestChallengeAnswer(suffix, state);
                if (!answerChallengeResponse.hasGrantedToken()) {
                    return;
                }

                Logger.printInfo(() -> "Granted token: " + clientTokenResponse.getGrantedToken());
            } catch (Exception ex) {
                Logger.printException(() -> "generateToken failure", ex);
            }
        });
    }
}
