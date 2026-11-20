package co.aisaac.scrapers.public_apis.remoteok;

import co.aisaac.scrapers.Http;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Entities;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Scrapes Remote OK's public JSON API: one GET returns about 100 jobs with full html descriptions.
 * <p>
 * The first element of the array is a legal notice, not a job. Their terms ask you to link back to
 * the job on Remote OK (the saved url is theirs) and to credit Remote OK.
 * <p>
 * {@code tag=dev} narrows it to development jobs, but their tags are loose, so some support and sales
 * jobs still come through. The API's text is double encoded (UTF-8 read as Latin-1, "Bogotá" arrives as "BogotÃ¡"),
 * so it's repaired before saving.
 */
public class ScrapeRemoteOk {

	private static final String API_URL = "https://remoteok.com/api?tag=dev";
	private static final String JOB_SITE = "remoteok";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeRemoteOk().run();
	}

	public void run() {
		Optional<String> body = Http.get(API_URL);
		if (body.isEmpty()) {
			System.out.println(JOB_SITE + ": couldn't fetch the API");
			return;
		}

		JsonNode postings;
		try {
			postings = mapper.readTree(body.get());
		} catch (Exception e) {
			System.out.println(JOB_SITE + ": couldn't read the API response: " + e.getMessage());
			return;
		}

		int found = 0;
		int saved = 0;

		for (JsonNode posting : postings) {
			// Skips the legal notice at the start.
			if (!posting.has("position")) {
				continue;
			}
			found++;

			Job job = new Job();
			job.url = posting.path("url").asText("").trim().replace("https://remoteOK.com/", "https://remoteok.com/");
			job.title = fixEncoding(posting.path("position").asText("")).trim();
			job.company = fixEncoding(posting.path("company").asText("")).trim();
			job.description = summary(posting) + fixEncoding(posting.path("description").asText(""));
			job.job_site = JOB_SITE;
			job.status = "new";
			job.job_posting_date = LocalDate.now();

			if (job.url.isEmpty() || job.title.isEmpty()) {
				continue;
			}
			if (store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	// Location and salary are separate fields, not always in the description.
	private static String summary(JsonNode posting) {
		StringBuilder html = new StringBuilder();

		String location = fixEncoding(posting.path("location").asText("")).trim().replaceAll("[,\\s]+$", "");
		appendLine(html, "Location", location);

		// Some jobs have nonsense like 21 to 21, so only real yearly salaries are shown.
		long min = posting.path("salary_min").asLong(0);
		long max = posting.path("salary_max").asLong(0);
		if (min >= 1000) {
			appendLine(html, "Salary", max > min ? "$" + min / 1000 + "k - $" + max / 1000 + "k" : "$" + min / 1000 + "k");
		}

		return html.toString();
	}

	private static void appendLine(StringBuilder html, String label, String value) {
		if (!value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.trim())).append("</p>\n");
		}
	}

	/**
	 * Undoes UTF-8 that was decoded as Latin-1: "BogotÃ¡" -> "Bogotá".
	 * Only when every character fits in Latin-1 and the bytes are valid UTF-8, so correct text is left alone.
	 */
	static String fixEncoding(String s) {
		boolean nonAscii = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c > 0xFF) {
				return s;
			}
			if (c > 0x7F) {
				nonAscii = true;
			}
		}
		if (!nonAscii) {
			return s;
		}

		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1)))
					.toString();
		} catch (CharacterCodingException e) {
			return s;
		}
	}
}
