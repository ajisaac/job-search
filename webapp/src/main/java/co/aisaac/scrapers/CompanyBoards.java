package co.aisaac.scrapers;

import org.jsoup.nodes.Entities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared by the company job board scrapers (Greenhouse, Lever, Ashby, Workable, SmartRecruiters, Recruitee).
 * Those boards have no "all jobs" endpoint, only one per company, so each reads its companies from a text file:
 *
 * <pre>
 * companies/
 *   greenhouse.txt
 *   lever.txt
 *   ...
 * </pre>
 * <p>
 * Next to the {@code jobs/} folder, in the folder the scraper runs from. One company per line: the company's
 * name in the board's urls, then optionally its display name. Blank lines and lines starting with # are skipped.
 *
 * <pre>
 * # jobs.lever.co/spotify
 * spotify Spotify
 * palantir
 * </pre>
 * <p>
 * Big companies post hundreds of jobs, mostly sales, legal, finance and so on, so only titles that look
 * technical are kept ({@link #isTechTitle}). The filter is loose on purpose; the webapp's filters do the rest.
 */
public class CompanyBoards {

	private static final Path DIR = Path.of("companies");

	private static final Pattern TECH_TITLE = Pattern.compile(
			"\\b(engineer|engineering|developer|development|software|programmer|devops|devsecops|sre|site reliability"
					+ "|data|machine learning|ml|ai|backend|back[- ]end|frontend|front[- ]end|full[- ]?stack|architect"
					+ "|platform|infrastructure|security|qa|test|mobile|ios|android|cloud|java|python|golang|rust)\\b",
			Pattern.CASE_INSENSITIVE);

	/**
	 * A company on a board. {@code slug} is its name in the board's urls, e.g. "spotify" in jobs.lever.co/spotify.
	 * {@code name} is the display name from the file, or empty.
	 */
	public record Company(String slug, String name) {

		/** The display name, or the slug if the file didn't give one. */
		public String nameOr(String fallback) {
			if (!name.isEmpty()) {
				return name;
			}
			return fallback == null || fallback.isBlank() ? slug : fallback.trim();
		}
	}

	/**
	 * Reads companies/{board}.txt. Returns an empty list, and says where to add companies, if the file is missing or empty.
	 */
	public static List<Company> read(String board) {
		Path file = DIR.resolve(board + ".txt");
		List<Company> companies = new ArrayList<>();

		if (Files.exists(file)) {
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					line = line.strip();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] parts = line.split("\\s+", 2);
					companies.add(new Company(parts[0], parts.length > 1 ? parts[1].strip() : ""));
				}
			} catch (IOException e) {
				System.out.println("Couldn't read " + file.toAbsolutePath() + ": " + e.getMessage());
			}
		}

		if (companies.isEmpty()) {
			System.out.println(board + ": no companies. Add them to " + file.toAbsolutePath() + ", one per line");
		}
		return companies;
	}

	public static boolean isTechTitle(String title) {
		return title != null && TECH_TITLE.matcher(title).find();
	}

	/** "<p><strong>Location:</strong> Remote</p>", or "" if the value is blank. */
	public static String line(String label, String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return "<p><strong>" + label + ":</strong> " + Entities.escape(value.strip()) + "</p>\n";
	}

	/** One line per company, e.g. "  gitlab: 253 jobs, 61 technical, 12 new". */
	public static void report(String slug, int jobs, int technical, int saved) {
		System.out.println("  " + slug + ": " + jobs + " jobs, " + technical + " technical, " + saved + " new");
	}
}
