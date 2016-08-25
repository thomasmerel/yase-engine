package fr.imie.yase.crawler;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import edu.uci.ics.crawler4j.crawler.Page;
import edu.uci.ics.crawler4j.parser.HtmlParseData;
import fr.imie.yase.database.dao.PageDAO;
import fr.imie.yase.database.dao.PageKeywordsDAO;
import fr.imie.yase.database.dao.WebSiteDAO;
import fr.imie.yase.dto.Keywords;
import fr.imie.yase.dto.PageKeywords;
import fr.imie.yase.dto.WebSite;
import org.apache.commons.io.FilenameUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
/**
 * Cette classe permet d'insérer en base le contenu push par PageCrawler dans la liste listPage.
 * @author Erwan
 *
 */
public class ThreadCrawler extends Thread {

    private enum Pertinence{
        VERYHIGH(10),
        HIGH(7),
        MEDIUM(5),
        LOW(3),
        VERYLOW(0);

        int value;
        Pertinence(int value) {
            this.value = value;
        }
    }

    private final static Vector<String> commonWords = new Vector<>(Arrays.asList("coucou",
            "salut",
            "hello"));

	private final static String REGEX_SPECIAL_CHAR = "[^À-Ÿà-ÿ\\w-]";

	private Page page;
	private Map<String, PageKeywords> keywordsInPage = new HashMap<String, PageKeywords>();
	private List<PageKeywords> missingPageKeywords = new ArrayList<PageKeywords>();
	private List<PageKeywords> deletedPageKeywords = new ArrayList<PageKeywords>();
	private Map<String, Keywords> knownKeywords = new HashMap<String, Keywords>();

	/**
	 * Permet de déclencher le Thread
	 */
	public void run() {
		try {
			crawlPage();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e2) {
			e2.printStackTrace();
		} catch (URISyntaxException e3) {
			e3.printStackTrace();
		}
		Thread.currentThread().interrupt();
	}

	/**
	 * Permet de lancer le crawl de la page passé en paramètre
	 *
	 * @throws InterruptedException
	 * @throws IOException          Erreur lors de la récupération de la page par le parser html
	 */
	private void crawlPage() throws InterruptedException, IOException, URISyntaxException {
		WebSite website;
		Page crawlerPage = this.getPage();

		try {
			website = createWebSite(crawlerPage);
			fr.imie.yase.dto.Page page = createPage(crawlerPage, website);

			// Maintenant qu'on a la page, on récupère tous ses mots liés.
			this.knownKeywords = this.generateKnownKeywords(page);

			// On parse la liste des mots
			this.keywordsInPage = this.parseKeywords(page, crawlerPage);

			// On regarde quels mots doivent être ajoutés
			this.missingPageKeywords = this.checkForMissingPageKeywords(page);

			// On regarde quels mots doivent être supprimés
			this.deletedPageKeywords = this.checkForDeletedPageKeywords(page);

			this.addMissingPageKeywords();
			this.removeDeletedPageKeywords();

			HtmlParseData htmlParseData = (HtmlParseData) crawlerPage.getParseData();
			System.out.println("Ajout du site à la pool : " + htmlParseData.getTitle());
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Permet d'ajouter une page crawler en base de données.
	 *
	 * @param page
	 * @param website
	 * @throws SQLException
	 */
	public fr.imie.yase.dto.Page createPage(Page page, WebSite website) throws SQLException {
		// Get data page
		HtmlParseData htmlParseData = (HtmlParseData) page.getParseData();
		String html = htmlParseData.getHtml();
		// Create entity page
		fr.imie.yase.dto.Page entity = new fr.imie.yase.dto.Page();
		entity.setTitle(htmlParseData.getTitle());
		if (htmlParseData.getTitle().length() > 100) {
			entity.setTitle(htmlParseData.getTitle().substring(0, 97) + "...");
		}
		entity.setContent(html);
		entity.setCrawl_date(getDate());
		entity.setDescription(getDescription(htmlParseData));
		entity.setLoad_time(1);
		entity.setLocale(page.getLanguage());
		entity.setSize(html.length());
		entity.setUrl(page.getWebURL().getURL());
		entity.setWebsite(website);

		// Insert page
		PageDAO daoPage = new PageDAO();
		// On vérifie si la page existe déjà en base
		fr.imie.yase.dto.Page result = daoPage.findByURL(entity);
		if (result.getId() == null) {
			daoPage.create(entity);
		}
		return entity;
	}

	/**
	 * Permet de générer une entity WebSite
	 *
	 * @param page Page
	 * @return website WebSite
	 * @throws SQLException
	 */
	public WebSite createWebSite(Page page) throws SQLException {
		String domain = page.getWebURL().getDomain();
		String url = page.getWebURL().getURL();
		String protocol = url.substring(0, url.indexOf("://"));
		WebSite website = new WebSite(null, domain, protocol);
		WebSiteDAO daoWebSite = new WebSiteDAO();
		List<WebSite> results = daoWebSite.find(website);
		WebSite websiteEntity;
		if (results.size() > 0) {
			websiteEntity = results.get(0);
		} else {
			websiteEntity = daoWebSite.create(website);
		}
		return websiteEntity;
	}

	/**
	 * Permet d'insérer un mot en base ou simplement de le récupérer.
	 *
	 * @param page
	 * @param crawlerPage
	 * @throws SQLException
	 */
	private Map<String, PageKeywords> parseKeywords(fr.imie.yase.dto.Page page, Page crawlerPage) throws SQLException, IOException, URISyntaxException {
		HashMap<String, Integer> tabWords = new HashMap<>();
		/**
		 * Parsing du html de la page depuis l'url
		 */
		Document doc = Jsoup.connect(page.getUrl()).get();
		/**
		 * Traitement des principaux tags html 4/5 confondus (suivant précos w3c)
		 */
		// Elements de headers
		Elements headerNodes = doc.select("header, div#header, div.header");
		for (Element elem : headerNodes) {
			for (Element node : elem.children()) {
				/**
				 * Pour chaque node, traitement du texte comme précédemment et ajout d'une valeur de pertinence
				 */
				if (!node.text().isEmpty()) {
					int note = Pertinence.VERYHIGH.value;
					System.out.println(String.format("Word: %s, Tag: %s, value: %d", node.text(), node.tagName(), note));
					updateWordsMap(tabWords, node.text(), note);
				}
			}
		}
		// Liens de barre(s) de navigation
		Elements navLinks = doc.select("div#nav li a,div.nav li a,nav li a");
		for (Element elem : navLinks) {
			for (Element node : elem.children()) {
				/**
				 * Pour chaque node, traitement du texte comme précédemment et ajout d'une valeur de pertinence
				 */
				if (!node.text().isEmpty()) {
					int note = Pertinence.HIGH.value;
					System.out.println(String.format("Word: %s, Tag: %s, value: %d", node.text(), node.tagName(), note));
					updateWordsMap(tabWords, node.text(), note);
				}
			}
		}
		// Liens des sidebars
		Elements sidebarLinks = doc.select("div[id^sidebar] li a, " +
				"div[class^sidebar] li a," +
				" aside li a," +
				" section[id^=sidebar] li a," +
				" section[class^=sidebar] li a");
		for (Element elem : sidebarLinks) {
			for (Element node : elem.children()) {
				/**
				 * Pour chaque node, traitement du texte comme précédemment et ajout d'une valeur de pertinence
				 */
				if (!node.text().isEmpty()) {
					int note = Pertinence.HIGH.value;
					System.out.println(String.format("Word: %s, Tag: %s, value: %d", node.text(), node.tagName(), note));
					updateWordsMap(tabWords, node.text(), note);
				}
			}
		}
		// Éléments du main
		Elements mainElements = doc.select("main," +
				"section#main, section.main," +
				"div#main, div.main");
		for (Element elem : mainElements) {
			for (Element node : elem.children()) {
				/**
				 * Pour chaque node, traitement du texte comme précédemment et ajout d'une valeur de pertinence
				 */
				if (!node.text().isEmpty() || node.tagName() == "img") {
					String word = node.text();
					int note = Pertinence.VERYLOW.value;
					switch (node.tagName()) {
						case "h1":
							note = Pertinence.HIGH.value;
							break;
						case "h2":
							note = Pertinence.MEDIUM.value;
							break;
						case "li":
							note = Pertinence.LOW.value;
							break;
						case "a":
							note = Pertinence.LOW.value;
							break;
						case "img":
							note = Pertinence.MEDIUM.value;
							word = node.attr("alt") != "" ? node.attr("alt") : FilenameUtils.getBaseName((new File((new URL(node.attr("src"))).toURI())).getPath());
						default:
							break;

					}

					System.out.println(String.format("Word: %s, Tag: %s, value: %d", node.text(), node.tagName(), note));
					updateWordsMap(tabWords, node.text(), note);
				}
			}
		}
		// Elements du corps
		// TODO: faire la même chose pour les autres éléments (main/footer/article/section/img etc...)
		// + changer dans certains node enfants la note suivant le tag parent(h1>h2>...>p etc.)
		// ex: Elements node = doc.select("div[id^sidebar], div[class^sidebar], aside, section[id^=sidebar], section[class^=sidebar]");

		/**
		 * sur la map finalement obtenue, effectuer le traitement précédent (légèrement modifié car HashMap<> != String[]
		 */

		Map<String, PageKeywords> ret = new HashMap<String, PageKeywords>();
		Iterator it = tabWords.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry entry = (Map.Entry) it.next();
			String currentKeyword = (String) entry.getKey();
			if (currentKeyword.length() > 0) {
				CrawlerDBManager manager = CrawlerDBManager.getInstance();
				Keywords keywordsEntity = manager.keywordExists(currentKeyword);
                PageKeywords pageKeywords = new PageKeywords(page.getId(), keywordsEntity.getId(), (Integer)entry.getValue());
				pageKeywords.setKeyword(keywordsEntity);
				ret.put(keywordsEntity.getValue(), pageKeywords);
			}
		}

		return ret;
	}

    /**
     * Mets à jour la map de mots donnée à partir d'un chaine de mots et d'une valeur donnés
     * @param map le tableau de mots à mettre à jour
     * @param chain la chaine de mots
     * @param note la note (pertinence) à rajouter aux mots
     */
    public void updateWordsMap(HashMap<String, Integer> map, String chain, int note) {
        String[] words = chain.replaceAll(REGEX_SPECIAL_CHAR, " ").split(" ");
        for(int i =0; i < words.length && i < 20; i++) {
            String currentWord = words[i].toLowerCase();
            // TODO: gérer ici le rejet des mots non significatifs (ex: liaisons...):
            // http://www.mycampus-live.com/telechargements/4-B2-argumentation-Tableaux-mots-de-liaison-et-modalisateurs.pdf
            if (map.containsKey(currentWord)) {
                int previousNote = map.get(currentWord);
                map.replace(currentWord,previousNote+note);
            } else {
                map.put(currentWord, note);
            }
        }
    }
    
    /**
     * Permet de récupérer la date du jour au format yyyy-MM-dd'T'HH:mmZ
     * @return date
     */
    public String getDate() {
    	TimeZone tz = TimeZone.getTimeZone("UTC");
    	DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");
    	df.setTimeZone(tz);
    	return df.format(new Date());
    }
    
    /**
     * Permet de récupérer la description d'un site
     * @param htmlParseData
     * @return description String
     */
    public String getDescription(HtmlParseData htmlParseData) {
    	String description = "";
    	String data = htmlParseData.getMetaTags().get("description");
    	if (data != null) {
    		description = data;
    		if (data.length() > 255) {
    			description = data.substring(0, 250) + "...";
    		}
    	}
    	return description;
    }
    
    /**
     * Permet d'ajouter la page à la liste
     * @param page
     */
    public void setPage(Page page) {
    	this.page = page;
    }
    
    /**
     * Permet de récupérer la prochaine page à insérer en bdd.
     * @return
     */
    private Page getPage() {
        return this.page;
    }

    private Map<String, Keywords> generateKnownKeywords(fr.imie.yase.dto.Page page) throws SQLException{
		PageKeywordsDAO pageKeywords = new PageKeywordsDAO();
		Map<String, Keywords> ret = new HashMap<String, Keywords>();

        List<Keywords> knownKeywords = pageKeywords.findAllKeywordsFromPage(page);

        for (Keywords k : knownKeywords) {
            ret.put(k.getValue(), k);
        }

		return ret;
	}

	private List<PageKeywords> checkForMissingPageKeywords(fr.imie.yase.dto.Page page){
		List<PageKeywords> ret = new ArrayList<PageKeywords>();

		for(Map.Entry<String, PageKeywords> p: this.keywordsInPage.entrySet()){
			if(!this.knownKeywords.containsKey(p.getValue().getKeyword().getValue())){
			    Keywords tmp = p.getValue().getKeyword();
				PageKeywords pageKeywords = new PageKeywords(page.getId(), tmp.getId(), p.getValue().getStrengh());
				ret.add(pageKeywords);
			}
		}

		return ret;
	}

	private List<PageKeywords> checkForDeletedPageKeywords(fr.imie.yase.dto.Page page){
		List<PageKeywords> ret = new ArrayList<PageKeywords>();

        for(Map.Entry<String, Keywords> k: this.knownKeywords.entrySet()){
        	if(!this.keywordsInPage.containsKey(k.getValue().getValue())){
        	    Keywords tmp = k.getValue();
				PageKeywords pageKeywords = new PageKeywords(page.getId(), tmp.getId(), 0);
				ret.add(pageKeywords);
			}
		}

		return ret;
	}

	private void addMissingPageKeywords() throws SQLException{
	    if(this.missingPageKeywords.size() > 0) {
			PageKeywordsDAO pageKeywordsDAO = new PageKeywordsDAO();
			pageKeywordsDAO.insertAllKeywords(this.missingPageKeywords);
		}
	}

	private void removeDeletedPageKeywords() throws SQLException{
		if(this.deletedPageKeywords.size() > 0){
			PageKeywordsDAO pageKeywordsDAO = new PageKeywordsDAO();
			pageKeywordsDAO.deleteAllKeywords(this.deletedPageKeywords);
		}
	}
}
