/*******************************************************************************
 * Copyright (c) 2015 Stichting Yona Foundation
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v.2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server

import groovy.json.*
import nu.yona.server.test.User

class OverwriteUserTest extends AbstractAppServiceIntegrationTest
{
	def 'Attempt to add another user with the same mobile number'()
	{
		given:
		def richard = addRichard()

		when:
		def duplicateUser = appService.addUser(this.&userExistsAsserter, "A n o t h e r", "The", "Next", "TN",
				"$richard.mobileNumber")

		then:
		duplicateUser == null

		cleanup:
		appService.deleteUser(richard)
	}

	def userExistsAsserter(def response)
	{
		assert response.status == 400
		assert response.responseData.code == "error.user.exists"
	}

	def 'Richard gets a confirmation code when requesting to overwrite his account'()
	{
		given:
		def richard = addRichard()

		when:
		def response = appService.requestOverwriteUser(richard.mobileNumber)

		then:
		response.status == 200

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Overwrite the existing user'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		analysisService.postToAnalysisEngine(richard, ["news/media"], "http://www.refdag.nl")
		appService.requestOverwriteUser(richard.mobileNumber)

		when:
		User richardChanged = appService.addUser(this.&assertUserOverwriteResponseDetails, "${richard.password}Changed", "${richard.firstName}Changed",
				"${richard.lastName}Changed", "${richard.nickname}Changed", richard.mobileNumber,
				["overwriteUserConfirmationCode": "1234"])

		then:
		richardChanged
		richardChanged.firstName == "${richard.firstName}Changed"
		richardChanged.lastName == "${richard.lastName}Changed"
		richardChanged.nickname == "${richard.nickname}Changed"
		richardChanged.mobileNumber == richard.mobileNumber
		richardChanged.goals.size() == 1 //mandatory goal
		richardChanged.goals[0].activityCategoryUrl == GAMBLING_ACT_CAT_URL

		def getMessagesResponse = appService.getMessages(bob)
		getMessagesResponse.status == 200
		def goalConflictMessages = getMessagesResponse.responseData._embedded."yona:messages".findAll{ it."@type" == "GoalConflictMessage"}
		goalConflictMessages.size() == 1
		goalConflictMessages[0].nickname == richard.nickname
		goalConflictMessages[0]._links."yona:activityCategory".href == NEWS_ACT_CAT_URL
		goalConflictMessages[0].url == null

		def buddies = appService.getBuddies(bob)
		buddies.size() == 1
		buddies[0].user == null
		buddies[0].nickname == richard.nickname // TODO: Shouldn't this change be communicated to Bob?
		buddies[0].sendingStatus == "ACCEPTED" // Shouldn't the status change now that the user is removed?
		buddies[0].receivingStatus == "ACCEPTED"

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Classification engine detects a potential conflict for Bob after Richard overwrote his account'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		appService.requestOverwriteUser(richard.mobileNumber)
		def richardChanged = appService.addUser(this.&assertUserOverwriteResponseDetails, "${richard.password}Changed", "${richard.firstName}Changed",
				"${richard.lastName}Changed", "${richard.nickname}Changed", richard.mobileNumber,
				["overwriteUserConfirmationCode": "1234"])

		when:
		def response = analysisService.postToAnalysisEngine(bob, ["Gambling"], "http://www.poker.com")

		then:
		response.status == 200

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Bob removes overwritten user Richard as buddy'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		def richard = richardAndBob.richard
		def bob = richardAndBob.bob
		appService.requestOverwriteUser(richard.mobileNumber)
		def richardChanged = appService.addUser(this.&assertUserOverwriteResponseDetails, "${richard.password}Changed", "${richard.firstName}Changed",
				"${richard.lastName}Changed", "${richard.nickname}Changed", richard.mobileNumber,
				["overwriteUserConfirmationCode": "1234"])
		def buddy = appService.getBuddies(bob)[0]

		when:
		def response = appService.removeBuddy(bob, buddy, "Good bye friend")

		then:
		response.status == 200
		appService.getBuddies(bob).size() == 0

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Hacking attempt: Brute force overwrite user overwrite confirmation'()
	{
		given:
		def userCreationMobileNumber = "+${timestamp}99"
		def userCreationJSON = """{
						"firstName":"John",
						"lastName":"Doe",
						"nickname":"JD",
						"mobileNumber":"${userCreationMobileNumber}"}"""

		def userAddResponse = appService.addUser(userCreationJSON, "Password")
		def overwriteRequestResponse = appService.requestOverwriteUser(userCreationMobileNumber)
		def userURL = YonaServer.stripQueryString(userAddResponse.responseData._links.self.href)

		when:
		def response1TimeWrong = appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "12341"])
		response1TimeWrong.responseData.remainingAttempts == 4
		appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "12342"])
		appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "12343"])
		def response4TimesWrong = appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "12344"])
		def response5TimesWrong = appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "12345"])
		def response6TimesWrong = appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "12346"])
		def response7thTimeRight = appService.addUser(userCreationJSON, "New password", ["overwriteUserConfirmationCode": "1234"])

		then:
		userAddResponse.status == 201
		userAddResponse.responseData._links."yona:confirmMobileNumber".href != null
		response1TimeWrong.status == 400
		response1TimeWrong.responseData.code == "error.user.overwrite.confirmation.code.mismatch"
		response1TimeWrong.responseData.remainingAttempts == 4
		response4TimesWrong.status == 400
		response4TimesWrong.responseData.code == "error.user.overwrite.confirmation.code.mismatch"
		response4TimesWrong.responseData.remainingAttempts == 1
		response5TimesWrong.status == 400
		response5TimesWrong.responseData.code == "error.user.overwrite.confirmation.code.mismatch"
		response5TimesWrong.responseData.remainingAttempts == 0
		response6TimesWrong.status == 400
		response6TimesWrong.responseData.code == "error.user.overwrite.confirmation.code.too.many.failed.attempts"
		response6TimesWrong.responseData.remainingAttempts == null
		response7thTimeRight.status == 400
		response7thTimeRight.responseData.code == "error.user.overwrite.confirmation.code.too.many.failed.attempts"

		cleanup:
		if (userURL)
		{
			appService.deleteUser(userURL, "Password")
		}
	}

	def assertUserOverwriteResponseDetails(def response)
	{
		appService.assertResponseStatusCreated(response)
		appService.assertUserWithPrivateData(response.responseData)
	}
}
