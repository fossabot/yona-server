package nu.yona.server.test

import groovy.json.*

class AnalysisService extends Service
{
	final ANALYSIS_ENGINE_PATH = "/analysisEngine/"
	final RELEVANT_CATEGORIES_PATH_FRAGMENT = "/relevantCategories/"

	JsonSlurper jsonSlurper = new JsonSlurper()

	AnalysisService ()
	{
		super("yona.analysisservice.url", "http://localhost:8080")
	}

	def getRelevantCategories()
	{
		yonaServer.getResource(ANALYSIS_ENGINE_PATH + RELEVANT_CATEGORIES_PATH_FRAGMENT)
	}

	def postToAnalysisEngine(vpnLoginID, categories, url)
	{
		def categoriesString = makeStringList(categories)
		postToAnalysisEngine("""{
					"vpnLoginID":"$vpnLoginID",
					"categories": [$categoriesString],
					"url":"$url"
				}""")
	}

	def postToAnalysisEngine(jsonString)
	{
		yonaServer.postJson(ANALYSIS_ENGINE_PATH, jsonString);
	}
}
