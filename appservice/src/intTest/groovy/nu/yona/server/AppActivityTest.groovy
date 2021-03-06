/*******************************************************************************
 * Copyright (c) 2015, 2018 Stichting Yona Foundation
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v.2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server

import static nu.yona.server.test.CommonAssertions.*

import java.time.Duration
import java.time.ZonedDateTime

import groovy.json.*
import nu.yona.server.test.AppActivity

class AppActivityTest extends AbstractAppServiceIntegrationTest
{
	def 'Hacking attempt: Try to post app activity without password'()
	{
		given:
		def richard = addRichard()
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minus(Duration.ofHours(1))
		ZonedDateTime endTime = testStartTime
		def nowString = YonaServer.toIsoDateTimeString(testStartTime)
		def startTimeString = YonaServer.toIsoDateTimeString(startTime)
		def endTimeString = YonaServer.toIsoDateTimeString(endTime)

		when:
		def response = appService.createResourceWithPassword(richard.appActivityUrl, """{
				"deviceDateTime" : "$nowString",
				"activities" : [{
					"application":"Poker App",
					"startTime":"$startTimeString",
					"endTime":"$endTimeString"
				}]}""", "Hack")

		then:
		assertResponseStatus(response, 400)
		response.responseData.code == "error.decrypting.data"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Goal conflict of Richard is reported to Richard and Bob (post to legacy URL, YD-544)'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusHours(1)
		ZonedDateTime endTime = testStartTime

		when:
		def response = appService.createResourceWithPassword(richard.appActivityUrl, AppActivity.singleActivity("Poker App", startTime, endTime).getJson(), richard.password)

		then:
		assertResponseStatusOk(response)
		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		ZonedDateTime goalConflictTime = YonaServer.now
		def goalConflictMessagesRichard = getMessagesRichardResponse.responseData._embedded."yona:messages".findAll
		{ it."@type" == "GoalConflictMessage" }
		goalConflictMessagesRichard.size() == 1
		goalConflictMessagesRichard[0].nickname == "RQ (me)"
		assertEquals(goalConflictMessagesRichard[0].creationTime, goalConflictTime)
		goalConflictMessagesRichard[0]._links."yona:activityCategory".href == GAMBLING_ACT_CAT_URL

		def getMessagesBobResponse = appService.getMessages(bob)
		assertResponseStatusOk(getMessagesBobResponse)
		def goalConflictMessagesBob = getMessagesBobResponse.responseData._embedded."yona:messages".findAll
		{ it."@type" == "GoalConflictMessage" }
		goalConflictMessagesBob.size() == 1
		goalConflictMessagesBob[0].nickname == richard.nickname
		assertEquals(goalConflictMessagesBob[0].creationTime, goalConflictTime)
		assertEquals(goalConflictMessagesBob[0].activityStartTime, startTime)
		assertEquals(goalConflictMessagesBob[0].activityEndTime, endTime)
		goalConflictMessagesBob[0]._links."yona:activityCategory".href == GAMBLING_ACT_CAT_URL

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Goal conflict of Richard is reported to Richard and Bob'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusHours(1)
		ZonedDateTime endTime = testStartTime

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard, AppActivity.singleActivity("Poker App", startTime, endTime))

		then:
		assertResponseStatusOk(response)
		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		ZonedDateTime goalConflictTime = YonaServer.now
		def goalConflictMessagesRichard = getMessagesRichardResponse.responseData._embedded."yona:messages".findAll
		{ it."@type" == "GoalConflictMessage" }
		goalConflictMessagesRichard.size() == 1
		goalConflictMessagesRichard[0].nickname == "RQ (me)"
		assertEquals(goalConflictMessagesRichard[0].creationTime, goalConflictTime)
		goalConflictMessagesRichard[0]._links."yona:activityCategory".href == GAMBLING_ACT_CAT_URL

		def getMessagesBobResponse = appService.getMessages(bob)
		assertResponseStatusOk(getMessagesBobResponse)
		def goalConflictMessagesBob = getMessagesBobResponse.responseData._embedded."yona:messages".findAll
		{ it."@type" == "GoalConflictMessage" }
		goalConflictMessagesBob.size() == 1
		goalConflictMessagesBob[0].nickname == richard.nickname
		assertEquals(goalConflictMessagesBob[0].creationTime, goalConflictTime)
		assertEquals(goalConflictMessagesBob[0].activityStartTime, startTime)
		assertEquals(goalConflictMessagesBob[0].activityEndTime, endTime)
		goalConflictMessagesBob[0]._links."yona:activityCategory".href == GAMBLING_ACT_CAT_URL

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Device time difference is properly resolved'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		Duration offset = Duration.ofMinutes(45) // Off by 45 minutes
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusHours(1)
		ZonedDateTime endTime = testStartTime

		when:
		def testStartTimeWrong = testStartTime.plus(offset)
		def startTimeWrong = startTime.plus(offset)
		def endTimeWrong = endTime.plus(offset)
		def response = appService.postAppActivityToAnalysisEngine(richard, AppActivity.singleActivity(testStartTimeWrong, "Poker App", startTimeWrong, endTimeWrong))

		then:
		assertResponseStatusOk(response)
		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		ZonedDateTime goalConflictTime = YonaServer.now
		def goalConflictMessagesRichard = getMessagesRichardResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessagesRichard.size() == 1
		goalConflictMessagesRichard[0].nickname == "RQ (me)"
		assertEquals(goalConflictMessagesRichard[0].creationTime, goalConflictTime)
		goalConflictMessagesRichard[0]._links."yona:activityCategory".href == GAMBLING_ACT_CAT_URL

		def getMessagesBobResponse = appService.getMessages(bob)
		assertResponseStatusOk(getMessagesBobResponse)
		def goalConflictMessagesBob = getMessagesBobResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessagesBob.size() == 1
		goalConflictMessagesBob[0].nickname == richard.nickname
		assertEquals(goalConflictMessagesBob[0].creationTime, goalConflictTime)
		assertEquals(goalConflictMessagesBob[0].activityStartTime, startTime)
		assertEquals(goalConflictMessagesBob[0].activityEndTime, endTime)
		goalConflictMessagesBob[0]._links."yona:activityCategory".href == GAMBLING_ACT_CAT_URL

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Two conflicts within the conflict interval are reported as one message for each person'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusHours(1)
		ZonedDateTime endTime = testStartTime
		ZonedDateTime startTime1 = testStartTime.minusSeconds(10)
		ZonedDateTime endTime1 = testStartTime

		when:
		appService.postAppActivityToAnalysisEngine(richard, AppActivity.singleActivity("Poker App", startTime, endTime))
		analysisService.postToAnalysisEngine(richard, ["Gambling"], "http://www.poker.com")
		appService.postAppActivityToAnalysisEngine(richard, AppActivity.singleActivity("Lotto App", startTime1, endTime1))

		then:
		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		def goalConflictMessagesRichard = getMessagesRichardResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessagesRichard.size() == 1

		def getMessagesBobResponse = appService.getMessages(bob)
		assertResponseStatusOk(getMessagesBobResponse)
		def goalConflictMessagesBob = getMessagesBobResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessagesBob.size() == 1

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Send multiple app activities after offline period'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusHours(1)
		ZonedDateTime endTime = testStartTime.minusSeconds(10)
		ZonedDateTime startTime1 = endTime
		ZonedDateTime endTime1 = testStartTime

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard,
				new AppActivity([new AppActivity.Activity("Poker App", startTime, endTime), new AppActivity.Activity("Lotto App", , startTime1, endTime1)].toArray()))

		then:
		assertResponseStatusOk(response)
		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		ZonedDateTime goalConflictTime = YonaServer.now
		def goalConflictMessagesRichard = getMessagesRichardResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessagesRichard.size() == 1
		assertEquals(goalConflictMessagesRichard[0].creationTime, goalConflictTime)
		assertEquals(goalConflictMessagesRichard[0].activityStartTime, startTime)
		assertEquals(goalConflictMessagesRichard[0].activityEndTime, endTime)

		def getMessagesBobResponse = appService.getMessages(bob)
		assertResponseStatusOk(getMessagesBobResponse)
		def goalConflictMessagesBob = getMessagesBobResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessagesBob.size() == 1
		assertEquals(goalConflictMessagesRichard[0].creationTime, goalConflictTime)
		assertEquals(goalConflictMessagesRichard[0].activityStartTime, startTime)
		assertEquals(goalConflictMessagesRichard[0].activityEndTime, endTime)

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Activity before goal creation is ignored'()
	{
		given:
		def richard = addRichard()
		setCreationTimeOfMandatoryGoalsToNow(richard)
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusDays(1)
		ZonedDateTime endTime = startTime.plusMinutes(15)

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard,
				new AppActivity([new AppActivity.Activity("Poker App", startTime, endTime)].toArray()))

		then:
		assertResponseStatusOk(response)
		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		ZonedDateTime goalConflictTime = YonaServer.now
		getMessagesRichardResponse.responseData._embedded?."yona:messages"?.findAll{ it."@type" == "GoalConflictMessage"} == null

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Try send app activity with end before start'()
	{
		given:
		def richard = addRichard()
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.minusDays(1)
		ZonedDateTime endTime = startTime.minusMinutes(15)

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard,
				new AppActivity([new AppActivity.Activity("Poker App", startTime, endTime)].toArray()))

		then:
		assertResponseStatus(response, 400)
		response.responseData.code == "error.analysis.invalid.app.activity.data.end.before.start"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Try send app activity with end in future'()
	{
		given:
		def richard = addRichard()
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime
		ZonedDateTime endTime = startTime.plusMinutes(1)

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard,
				new AppActivity([new AppActivity.Activity("Poker App", startTime, endTime)].toArray()))

		then:
		assertResponseStatus(response, 400)
		response.responseData.code == "error.analysis.invalid.app.activity.data.ends.in.future"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Try send app activity with start in future'()
	{
		given:
		def richard = addRichard()
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, "W-1 Mon 02:18")
		ZonedDateTime testStartTime = YonaServer.now
		ZonedDateTime startTime = testStartTime.plusMinutes(1)
		ZonedDateTime endTime = startTime.plusMinutes(1)

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard,
				new AppActivity([new AppActivity.Activity("Poker App", startTime, endTime)].toArray()))

		then:
		assertResponseStatus(response, 400)
		response.responseData.code == "error.analysis.invalid.app.activity.data.starts.in.future"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Send app activity within device time inacuracy window'()
	{
		given:
		def richard = addRichard()
		String goalCreationTimeStr = "W-1 Mon 02:18"
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, goalCreationTimeStr)
		ZonedDateTime testStartTime = YonaServer.now
		// Device time inaccuracy window is plus or minus 10 seconds.
		// Try posting an event that starts 5 seconds before the goal creation time and ends 5 seconds in the future
		ZonedDateTime startTime = YonaServer.relativeDateTimeStringToZonedDateTime(goalCreationTimeStr).minusSeconds(5)
		ZonedDateTime endTime = testStartTime.plusSeconds(5)

		when:
		def response = appService.postAppActivityToAnalysisEngine(richard,
				new AppActivity([new AppActivity.Activity("Poker App", startTime, endTime)].toArray()))

		then:
		assertResponseStatusOk(response)

		def getMessagesRichardResponse = appService.getMessages(richard)
		assertResponseStatusOk(getMessagesRichardResponse)
		getMessagesRichardResponse.responseData._embedded?."yona:messages"?.findAll{ it."@type" == "GoalConflictMessage"}?.size()
		// Don't poke into the messages. The app activity spans many days and we only support activities spanning at most two days

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Overlapping network and app activity is counted only once'()
	{
		given:
		def richard = addRichard()
		String goalCreationTimeStr = "W-1 Mon 02:18"
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, goalCreationTimeStr)
		ZonedDateTime goalCreationTime = YonaServer.relativeDateTimeStringToZonedDateTime(goalCreationTimeStr)
		ZonedDateTime appActStartTime = goalCreationTime.plusHours(1)
		ZonedDateTime appActEndTime = appActStartTime.plusMinutes(20)
		def appActivity = new AppActivity.Activity("Poker App", appActStartTime, appActEndTime)
		ZonedDateTime netActStartTime = appActStartTime.plusMinutes(10)

		when:
		analysisService.postToAnalysisEngine(richard, ["Gambling"], "http://www.poker.com", netActStartTime)
		assertResponseStatusOk(appService.postAppActivityToAnalysisEngine(richard, new AppActivity([appActivity].toArray())))

		then:
		def response = appService.getDayDetails(richard, GAMBLING_ACT_CAT_URL, appActStartTime)
		assertResponseStatusOk(response)
		assert response.responseData.totalActivityDurationMinutes == Duration.between(appActStartTime, appActEndTime).toMinutes()

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Duplicate app activity reported out of sequence is counted only once'()
	{
		given:
		def richard = addRichard()
		String goalCreationTimeStr = "W-1 Mon 02:18"
		setGoalCreationTime(richard, GAMBLING_ACT_CAT_URL, goalCreationTimeStr)
		ZonedDateTime goalCreationTime = YonaServer.relativeDateTimeStringToZonedDateTime(goalCreationTimeStr)
		ZonedDateTime appActOneStartTime = goalCreationTime.plusHours(1)
		ZonedDateTime appActOneEndTime = appActOneStartTime.plusMinutes(20)
		ZonedDateTime appActTwoStartTime = appActOneStartTime.plusMinutes(5)
		ZonedDateTime appActTwoEndTime = appActTwoStartTime.plusMinutes(20)
		def appActOne = new AppActivity.Activity("Poker App", appActOneStartTime, appActOneEndTime)
		def appActTwo = new AppActivity.Activity("Poker App", appActTwoStartTime, appActTwoEndTime)
		ZonedDateTime netActStartTime = goalCreationTime.plusHours(2)
		int netActDuration = 1 // Default duration for a network activity

		when:
		analysisService.postToAnalysisEngine(richard, ["Gambling"], "http://www.poker.com", netActStartTime)
		assertResponseStatusOk(appService.postAppActivityToAnalysisEngine(richard, new AppActivity([appActOne, appActTwo, appActOne].toArray())))

		then:
		def response = appService.getDayDetails(richard, GAMBLING_ACT_CAT_URL, appActOneStartTime)
		assertResponseStatusOk(response)
		assert response.responseData.totalActivityDurationMinutes == Duration.between(appActOneStartTime, appActTwoEndTime).toMinutes() + netActDuration

		cleanup:
		appService.deleteUser(richard)
	}
}
