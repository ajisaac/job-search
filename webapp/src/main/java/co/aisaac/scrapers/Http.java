package co.aisaac.scrapers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Plain HTTP GETs for the scrapers that don't need a browser.
 * Keeps the User-Agent, timeout and error handling in one place.
 */
public class Http {

	private static final String USER_AGENT =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	/**
	 * @return the response body, or empty if the request failed or didn't return 200. Failures are logged.
	 */
	public static Optional<String> get(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(30))
					.header("User-Agent", USER_AGENT)
					.header("Accept-Language", "en-US,en;q=0.9")
					.GET()
					.build();

			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				System.out.println("GET " + url + " returned " + response.statusCode());
				return Optional.empty();
			}
			return Optional.of(response.body());

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.out.println("GET " + url + " interrupted");
			return Optional.empty();
		} catch (Exception e) {
			System.out.println("GET " + url + " failed: " + e);
			return Optional.empty();
		}
	}
}
