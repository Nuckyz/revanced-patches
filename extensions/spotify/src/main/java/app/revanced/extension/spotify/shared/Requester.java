package app.revanced.extension.spotify.shared;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class Requester {

    public static HttpURLConnection getProtobufRequestConnection(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);

        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");
        connection.setRequestProperty("Accept", "application/x-protobuf");

        return connection;
    }
}
