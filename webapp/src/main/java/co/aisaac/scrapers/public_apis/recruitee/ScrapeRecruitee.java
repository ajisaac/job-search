package co.aisaac.scrapers.public_apis.recruitee;

import co.aisaac.scrapers.CompanyBoards;
import co.aisaac.scrapers.CompanyBoards.Company;
import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes Recruitee careers sites, one GET per company from companies/recruitee.txt, full descriptions included.
 * The company is its name in {company}.recruitee.com. A description comes in two parts,
 * {@code description} and {@code requirements}, which are joined here.
 */
public class ScrapeRecruitee {

	private static final String JOB_SITE = "recruitee";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeRecruitee().run();
	}

	public void run() {
		List<Company> companies = CompanyBoards.read(JOB_SITE);
		int found = 0;
		int saved = 0;

		for (int i = 0; i < companies.size(); i++) {
			if (i > 0) {
				Utils.sleepRandom(3);
			}
			Company company = companies.get(i);

			Optional<JsonNode> response = readJson("https://" + company.slug() + ".recruitee.com/api/offers/");
			if (response.isEmpty()) {
				continue;
			}

			JsonNode postings = response.get().path("offers");
			int technical = 0;
			int savedHere = 0;

			for (JsonNode posting : postings) {
				String title = posting.path("title").asText("").strip();
				if (!"published".equals(posting.path("status").asText("published")) || !CompanyBoards.isTechTitle(title)) {
					continue;
				}
				technical++;

				Job job = new Job();
				job.url = posting.path("careers_url").asText("").strip();
				job.title = title;
				job.company = company.nameOr(posting.path("company_name").asText(""));
				job.description = summary(posting)
						+ posting.path("description").asText("")
						+ posting.path("requirements").asText("");
				job.job_site = JOB_SITE;
				job.status = "new";
				job.job_posting_date = LocalDate.now();

				if (!job.url.isEmpty() && store.saveNew(job)) {
					savedHere++;
				}
			}

			CompanyBoards.report(company.slug(), postings.size(), technical, savedHere);
			found += technical;
			saved += savedHere;
		}

		System.out.println(JOB_SITE + ": " + companies.size() + " companies, " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode posting) {
		String workplace = posting.path("remote").asBoolean(false) ? "Remote"
				: posting.path("hybrid").asBoolean(false) ? "Hybrid"
				: posting.path("on_site").asBoolean(false) ? "On site" : "";

		JsonNode salary = posting.path("salary");
		String pay = "";
		if (!salary.path("min").isNull() && !salary.path("min").isMissingNode()) {
			pay = salary.path("min").asText() + " - " + salary.path("max").asText("") + " "
					+ salary.path("currency").asText("") + " " + salary.path("period").asText("");
		}

		return CompanyBoards.line("Location", posting.path("location").asText(""))
				+ CompanyBoards.line("Workplace", workplace)
				+ CompanyBoards.line("Type", posting.path("employment_type_code").asText("").replace('_', ' '))
				+ CompanyBoards.line("Salary", pay.replaceAll("\\s+", " ").strip());
	}

	private Optional<JsonNode> readJson(String url) {
		return Http.get(url).flatMap(body -> {
			try {
				return Optional.of(mapper.readTree(body));
			} catch (Exception e) {
				System.out.println("Couldn't read " + url + ": " + e.getMessage());
				return Optional.empty();
			}
		});
	}
}
