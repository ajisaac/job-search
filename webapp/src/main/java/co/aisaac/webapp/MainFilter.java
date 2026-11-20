package co.aisaac.webapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainFilter {

	String status = "new";
	String jobSite = "all";

	// What was typed in the search box, e.g. "java, kotlin, title: senior, company: acme"
	String query = "";

	// Parsed out of the query, each comma separated
	String companySearch = "";
	String searchTerms = "";
	String titleSearch = "";

	/**
	 * Builds a filter from the page's url parameters. The search box holds comma separated parts;
	 * "title: x" and "company: x" filter those fields, anything else is a search term.
	 */
	public static MainFilter of(String status, String jobSite, String query) {
		MainFilter filter = new MainFilter();
		filter.status = status;
		filter.jobSite = jobSite;
		filter.query = query;

		List<String> terms = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		List<String> companies = new ArrayList<>();

		for (String part : query.split(",")) {
			String p = part.trim();
			String lower = p.toLowerCase(Locale.ROOT);
			if (lower.startsWith("title:")) {
				titles.add(p.substring("title:".length()).trim());
			} else if (lower.startsWith("company:")) {
				companies.add(p.substring("company:".length()).trim());
			} else if (!p.isEmpty()) {
				terms.add(p);
			}
		}

		filter.searchTerms = String.join(",", terms);
		filter.titleSearch = String.join(",", titles);
		filter.companySearch = String.join(",", companies);
		return filter;
	}

	public String getJobSite() {
		return jobSite.toLowerCase(Locale.ROOT);
	}

	public String getStatus() {
		return status.toLowerCase(Locale.ROOT);
	}

	public String getQuery() {
		return query;
	}

	public String getCompanySearch() {
		return companySearch;
	}

	public String getSearchTerms() {
		return searchTerms;
	}

	public String getTitleSearch() {
		return titleSearch;
	}
}
