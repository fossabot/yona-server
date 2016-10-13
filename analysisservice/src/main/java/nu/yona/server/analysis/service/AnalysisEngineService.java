/*******************************************************************************
 * Copyright (c) 2015, 2016 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.analysis.service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import nu.yona.server.analysis.entities.Activity;
import nu.yona.server.analysis.entities.DayActivity;
import nu.yona.server.analysis.entities.DayActivityRepository;
import nu.yona.server.analysis.entities.GoalConflictMessage;
import nu.yona.server.analysis.entities.WeekActivity;
import nu.yona.server.analysis.entities.WeekActivityRepository;
import nu.yona.server.exceptions.AnalysisException;
import nu.yona.server.goals.entities.Goal;
import nu.yona.server.goals.service.ActivityCategoryDTO;
import nu.yona.server.goals.service.ActivityCategoryService;
import nu.yona.server.goals.service.GoalDTO;
import nu.yona.server.goals.service.GoalService;
import nu.yona.server.messaging.service.MessageService;
import nu.yona.server.properties.YonaProperties;
import nu.yona.server.subscriptions.entities.UserAnonymized;
import nu.yona.server.subscriptions.service.UserAnonymizedDTO;
import nu.yona.server.subscriptions.service.UserAnonymizedService;
import nu.yona.server.util.LockPool;

@Service
public class AnalysisEngineService
{
	private static final Duration DEVICE_TIME_INACCURACY_MARGIN = Duration.ofSeconds(10);
	private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
	@Autowired
	private YonaProperties yonaProperties;
	@Autowired
	private ActivityCategoryService activityCategoryService;
	@Autowired
	private ActivityCacheService cacheService;
	@Autowired
	private UserAnonymizedService userAnonymizedService;
	@Autowired
	private GoalService goalService;
	@Autowired
	private MessageService messageService;
	@Autowired(required = false)
	private DayActivityRepository dayActivityRepository;
	@Autowired(required = false)
	private WeekActivityRepository weekActivityRepository;
	@Autowired
	private LockPool<UUID> userAnonymizedSynchronizer;

	@Transactional
	public void analyze(UUID userAnonymizedID, AppActivityDTO appActivities)
	{
		UserAnonymizedDTO userAnonymized = userAnonymizedService.getUserAnonymized(userAnonymizedID);
		Duration deviceTimeOffset = determineDeviceTimeOffset(appActivities);
		for (AppActivityDTO.Activity appActivity : appActivities.getActivities())
		{
			Set<ActivityCategoryDTO> matchingActivityCategories = activityCategoryService
					.getMatchingCategoriesForApp(appActivity.getApplication());
			analyze(createActivityPayload(deviceTimeOffset, appActivity, userAnonymized), matchingActivityCategories);
		}
	}

	@Transactional
	public void analyze(UUID userAnonymizedID, NetworkActivityDTO networkActivity)
	{
		UserAnonymizedDTO userAnonymized = userAnonymizedService.getUserAnonymized(userAnonymizedID);
		Set<ActivityCategoryDTO> matchingActivityCategories = activityCategoryService
				.getMatchingCategoriesForSmoothwallCategories(networkActivity.getCategories());
		analyze(ActivityPayload.createInstance(userAnonymized, networkActivity), matchingActivityCategories);
	}

	private Duration determineDeviceTimeOffset(AppActivityDTO appActivities)
	{
		Duration offset = Duration.between(ZonedDateTime.now(), appActivities.getDeviceDateTime());
		return (offset.abs().compareTo(DEVICE_TIME_INACCURACY_MARGIN) > 0) ? offset : Duration.ZERO; // Ignore if less than 10
																										// seconds
	}

	private ActivityPayload createActivityPayload(Duration deviceTimeOffset, AppActivityDTO.Activity appActivity,
			UserAnonymizedDTO userAnonymized)
	{
		ZonedDateTime correctedStartTime = correctTime(deviceTimeOffset, appActivity.getStartTime());
		ZonedDateTime correctedEndTime = correctTime(deviceTimeOffset, appActivity.getEndTime());
		String application = appActivity.getApplication();
		validateTimes(userAnonymized, application, correctedStartTime, correctedEndTime);
		return ActivityPayload.createInstance(userAnonymized, correctedStartTime, correctedEndTime, application);
	}

	private void validateTimes(UserAnonymizedDTO userAnonymized, String application, ZonedDateTime correctedStartTime,
			ZonedDateTime correctedEndTime)
	{
		if (correctedEndTime.isBefore(correctedStartTime))
		{
			throw AnalysisException.appActivityStartAfterEnd(userAnonymized.getID(), application, correctedStartTime,
					correctedEndTime);
		}
		if (correctedStartTime.isAfter(ZonedDateTime.now().plus(DEVICE_TIME_INACCURACY_MARGIN)))
		{
			throw AnalysisException.appActivityStartsInFuture(userAnonymized.getID(), application, correctedStartTime);
		}
		if (correctedEndTime.isAfter(ZonedDateTime.now().plus(DEVICE_TIME_INACCURACY_MARGIN)))
		{
			throw AnalysisException.appActivityEndsInFuture(userAnonymized.getID(), application, correctedEndTime);
		}
	}

	private ZonedDateTime correctTime(Duration deviceTimeOffset, ZonedDateTime time)
	{
		return time.minus(deviceTimeOffset);
	}

	private void analyze(ActivityPayload payload, Set<ActivityCategoryDTO> matchingActivityCategories)
	{
		Set<GoalDTO> matchingGoalsOfUser = determineMatchingGoalsForUser(payload.userAnonymized, matchingActivityCategories,
				payload.startTime);
		for (GoalDTO matchingGoalOfUser : matchingGoalsOfUser)
		{
			addOrUpdateActivity(payload, matchingGoalOfUser);
		}
	}

	private void addOrUpdateActivity(ActivityPayload payload, GoalDTO matchingGoal)
	{
		if (isCrossDayActivity(payload))
		{
			// assumption: activity never crosses 2 days
			ActivityPayload truncatedPayload = ActivityPayload.copyTillEndTime(payload,
					getEndOfDay(payload.startTime, payload.userAnonymized));
			ActivityPayload nextDayPayload = ActivityPayload.copyFromStartTime(payload,
					getStartOfDay(payload.endTime, payload.userAnonymized));

			addOrUpdateDayTruncatedActivity(truncatedPayload, matchingGoal);
			addOrUpdateDayTruncatedActivity(nextDayPayload, matchingGoal);
		}
		else
		{
			addOrUpdateDayTruncatedActivity(payload, matchingGoal);
		}
	}

	private void addOrUpdateDayTruncatedActivity(ActivityPayload payload, GoalDTO matchingGoal)
	{
		ActivityCacheResult activity = getRegisteredDayActivity(payload, matchingGoal);
		if (canCombineWithLastRegisteredActivity(payload, activity.content))
		{
			if (isBeyondSkipWindowAfterLastRegisteredActivity(payload, activity.content))
			{
				// Update message only if it is within five seconds to avoid unnecessary cache flushes.
				updateActivityEndTime(payload, matchingGoal, activity);
			}
		}
		else
		{
			addActivity(payload, matchingGoal, activity);
		}
	}

	private boolean isBeyondSkipWindowAfterLastRegisteredActivity(ActivityPayload payload, ActivityDTO lastRegisteredActivity)
	{
		return Duration.between(lastRegisteredActivity.getEndTime(), payload.endTime)
				.compareTo(yonaProperties.getAnalysisService().getUpdateSkipWindow()) >= 0;
	}

	private boolean canCombineWithLastRegisteredActivity(ActivityPayload payload, ActivityDTO lastRegisteredActivity)
	{
		if (lastRegisteredActivity == null)
		{
			return false;
		}

		if (precedesLastRegisteredActivity(payload, lastRegisteredActivity))
		{
			// This can happen with app activity.
			// Do not try to combine, add separately.
			return false;
		}

		if (isBeyondCombineIntervalWithLastRegisteredActivity(payload, lastRegisteredActivity))
		{
			return false;
		}

		return true;
	}

	private boolean precedesLastRegisteredActivity(ActivityPayload payload, ActivityDTO lastRegisteredActivity)
	{
		return payload.startTime.isBefore(lastRegisteredActivity.getStartTime());
	}

	private boolean isBeyondCombineIntervalWithLastRegisteredActivity(ActivityPayload payload, ActivityDTO lastRegisteredActivity)
	{
		ZonedDateTime intervalEndTime = lastRegisteredActivity.getEndTime()
				.plus(yonaProperties.getAnalysisService().getConflictInterval());
		return payload.startTime.isAfter(intervalEndTime);
	}

	private ActivityCacheResult getRegisteredDayActivity(ActivityPayload payload, GoalDTO matchingGoal)
	{
		ActivityDTO lastRegisteredActivity = cacheService.fetchLastActivityForUser(payload.userAnonymized.getID(),
				matchingGoal.getID());
		if (lastRegisteredActivity == null)
		{
			return ActivityCacheResult.cacheEmpty();
		}
		else if (hasLaterDay(lastRegisteredActivity, payload))
		{
			return ActivityCacheResult.cachedHasLaterDay();
		}
		else if (hasEarlierDay(lastRegisteredActivity, payload))
		{
			return ActivityCacheResult.cachedHasEarlierDay();
		}
		else
		{
			return ActivityCacheResult.fromCache(lastRegisteredActivity);
		}
	}

	private boolean hasEarlierDay(ActivityDTO lastRegisteredActivity, ActivityPayload payload)
	{
		return lastRegisteredActivity.getEndTime().isBefore(getStartOfDay(payload.startTime, payload.userAnonymized));
	}

	private boolean hasLaterDay(ActivityDTO lastRegisteredActivity, ActivityPayload payload)
	{
		return getStartOfDay(lastRegisteredActivity.getStartTime(), payload.userAnonymized).isAfter(payload.startTime);
	}

	private boolean isCrossDayActivity(ActivityPayload payload)
	{
		return getStartOfDay(payload.endTime, payload.userAnonymized).isAfter(payload.startTime);
	}

	private void addActivity(ActivityPayload payload, GoalDTO matchingGoal, ActivityCacheResult activityCacheResult)
	{
		Goal matchingGoalEntity = goalService.getGoalEntityForUserAnonymizedID(payload.userAnonymized.getID(),
				matchingGoal.getID());
		Activity addedActivity = createNewActivity(payload, matchingGoalEntity);
		if (shouldUpdateCache(activityCacheResult, addedActivity))
		{
			cacheService.updateLastActivityForUser(payload.userAnonymized.getID(), matchingGoal.getID(),
					ActivityDTO.createInstance(addedActivity));
		}

		if (matchingGoal.isNoGoGoal())
		{
			sendConflictMessageToAllDestinationsOfUser(payload, addedActivity, matchingGoalEntity);
		}
	}

	private void updateActivityEndTime(ActivityPayload payload, GoalDTO matchingGoal, ActivityCacheResult activityCacheResult)
	{
		DayActivity dayActivity = findExistingDayActivity(payload, matchingGoal.getID());
		// TODO: are we sure that getLastActivity() gives the same activity? no concurrency issues?
		Activity activity = dayActivity.getLastActivity();
		activity.setEndTime(payload.endTime);
		DayActivity updatedDayActivity = dayActivityRepository.save(dayActivity);
		// TODO: are we sure that getLastActivity() gives the same activity? no concurrency issues?
		Activity updatedActivity = updatedDayActivity.getLastActivity();
		if (shouldUpdateCache(activityCacheResult, updatedActivity))
		{
			cacheService.updateLastActivityForUser(payload.userAnonymized.getID(), matchingGoal.getID(),
					ActivityDTO.createInstance(updatedActivity));
		}
	}

	private boolean shouldUpdateCache(ActivityCacheResult activityCacheResult, Activity newOrUpdatedActivity)
	{
		if (activityCacheResult.content == null)
		{
			return activityCacheResult.status != ActivityCacheResultStatus.CACHED_HAS_LATER_DAY;
		}
		return !activityCacheResult.content.getEndTime().isAfter(newOrUpdatedActivity.getEndTime());
	}

	private ZonedDateTime getStartOfDay(ZonedDateTime time, UserAnonymizedDTO userAnonymized)
	{
		return time.withZoneSameInstant(ZoneId.of(userAnonymized.getTimeZoneId())).truncatedTo(ChronoUnit.DAYS);
	}

	private ZonedDateTime getEndOfDay(ZonedDateTime time, UserAnonymizedDTO userAnonymized)
	{
		return getStartOfDay(time, userAnonymized).plusHours(23).plusMinutes(59).plusSeconds(59);
	}

	private ZonedDateTime getStartOfWeek(ZonedDateTime time, UserAnonymizedDTO userAnonymized)
	{
		ZonedDateTime startOfDay = getStartOfDay(time, userAnonymized);
		switch (startOfDay.getDayOfWeek())
		{
			case SUNDAY:
				// take as the first day of week
				return startOfDay;
			default:
				// MONDAY=1, etc.
				return startOfDay.minusDays(startOfDay.getDayOfWeek().getValue());
		}
	}

	private Activity createNewActivity(ActivityPayload payload, Goal matchingGoal)
	{
		DayActivity dayActivity = findExistingDayActivity(payload, matchingGoal.getID());
		if (dayActivity == null)
		{
			dayActivity = createNewDayActivity(payload, matchingGoal);
		}

		ZonedDateTime endTime = ensureMinimumDurationOneMinute(payload);
		Activity activity = Activity.createInstance(payload.startTime, endTime);
		dayActivity.addActivity(activity);
		DayActivity updatedDayActivity = dayActivityRepository.save(dayActivity);
		// TODO: are we sure that getLastActivity() gives the same activity? no concurrency issues?
		return updatedDayActivity.getLastActivity();
	}

	private ZonedDateTime ensureMinimumDurationOneMinute(ActivityPayload payload)
	{
		Duration duration = Duration.between(payload.startTime, payload.endTime);
		if (duration.compareTo(ONE_MINUTE) < 0)
		{
			return payload.endTime.plus(ONE_MINUTE);
		}
		return payload.endTime;
	}

	private DayActivity createNewDayActivity(ActivityPayload payload, Goal matchingGoal)
	{
		try (LockPool<UUID>.Lock lock = userAnonymizedSynchronizer.lock(payload.userAnonymized.getID()))
		{
			// Within the lock, check that no other thread in the meanwhile created this day activity
			DayActivity existingDayActivity = findExistingDayActivity(payload, matchingGoal.getID());
			if (existingDayActivity != null)
			{
				return existingDayActivity;
			}
			return createNewDayActivityInsideLock(payload, matchingGoal);
		}
	}

	private DayActivity createNewDayActivityInsideLock(ActivityPayload payload, Goal matchingGoal)
	{
		UserAnonymized userAnonymizedEntity = userAnonymizedService.getUserAnonymizedEntity(payload.userAnonymized.getID());

		DayActivity dayActivity = DayActivity.createInstance(userAnonymizedEntity, matchingGoal,
				getStartOfDay(payload.startTime, payload.userAnonymized));

		ZonedDateTime startOfWeek = getStartOfWeek(payload.startTime, payload.userAnonymized);
		WeekActivity weekActivity = weekActivityRepository.findOne(payload.userAnonymized.getID(), matchingGoal.getID(),
				startOfWeek.toLocalDate());
		if (weekActivity == null)
		{
			weekActivity = WeekActivity.createInstance(userAnonymizedEntity, matchingGoal, startOfWeek);
		}
		dayActivityRepository.save(dayActivity);
		weekActivityRepository.save(weekActivity);

		return dayActivity;
	}

	private DayActivity findExistingDayActivity(ActivityPayload payload, UUID matchingGoalID)
	{
		return dayActivityRepository.findOne(payload.userAnonymized.getID(),
				getStartOfDay(payload.startTime, payload.userAnonymized).toLocalDate(), matchingGoalID);
	}

	@Transactional
	public Set<String> getRelevantSmoothwallCategories()
	{
		return activityCategoryService.getAllActivityCategories().stream().flatMap(g -> g.getSmoothwallCategories().stream())
				.collect(Collectors.toSet());
	}

	private void sendConflictMessageToAllDestinationsOfUser(ActivityPayload payload, Activity activity, Goal matchingGoal)
	{
		GoalConflictMessage selfGoalConflictMessage = GoalConflictMessage.createInstance(payload.userAnonymized.getID(), activity,
				matchingGoal, payload.url);
		messageService.sendMessage(selfGoalConflictMessage, payload.userAnonymized.getAnonymousDestination());

		messageService.broadcastMessageToBuddies(payload.userAnonymized,
				() -> GoalConflictMessage.createInstanceFromBuddy(payload.userAnonymized.getID(), selfGoalConflictMessage));
	}

	private Set<GoalDTO> determineMatchingGoalsForUser(UserAnonymizedDTO userAnonymized,
			Set<ActivityCategoryDTO> matchingActivityCategories, ZonedDateTime activityStartTime)
	{
		Set<UUID> matchingActivityCategoryIDs = matchingActivityCategories.stream().map(ac -> ac.getID())
				.collect(Collectors.toSet());
		Set<GoalDTO> goalsOfUser = userAnonymized.getGoals();
		Set<GoalDTO> matchingGoalsOfUser = goalsOfUser.stream().filter(g -> !g.isHistoryItem())
				.filter(g -> matchingActivityCategoryIDs.contains(g.getActivityCategoryID()))
				.filter(g -> g.getCreationTime().get().isBefore(activityStartTime.plus(DEVICE_TIME_INACCURACY_MARGIN)))
				.collect(Collectors.toSet());
		return matchingGoalsOfUser;
	}

	private enum ActivityCacheResultStatus
	{
		CACHE_EMPTY, FROM_CACHE, CACHED_HAS_EARLIER_DAY, CACHED_HAS_LATER_DAY
	}

	private static class ActivityCacheResult
	{
		public final ActivityCacheResultStatus status;
		public final ActivityDTO content;

		public ActivityCacheResult(ActivityDTO content, ActivityCacheResultStatus status)
		{
			this.status = status;
			this.content = content;
		}

		public static ActivityCacheResult cacheEmpty()
		{
			return new ActivityCacheResult(null, ActivityCacheResultStatus.CACHE_EMPTY);
		}

		public static ActivityCacheResult fromCache(ActivityDTO lastRegisteredActivity)
		{
			return new ActivityCacheResult(lastRegisteredActivity, ActivityCacheResultStatus.FROM_CACHE);
		}

		public static ActivityCacheResult cachedHasEarlierDay()
		{
			return new ActivityCacheResult(null, ActivityCacheResultStatus.CACHED_HAS_EARLIER_DAY);
		}

		public static ActivityCacheResult cachedHasLaterDay()
		{
			return new ActivityCacheResult(null, ActivityCacheResultStatus.CACHED_HAS_LATER_DAY);
		}
	}

	private static class ActivityPayload
	{
		public final UserAnonymizedDTO userAnonymized;
		public final Optional<String> url;
		public final ZonedDateTime startTime;
		public final ZonedDateTime endTime;
		public final Optional<String> application;

		private ActivityPayload(UserAnonymizedDTO userAnonymized, Optional<String> url, ZonedDateTime startTime,
				ZonedDateTime endTime, Optional<String> application)
		{
			this.userAnonymized = userAnonymized;
			this.url = url;
			this.startTime = startTime;
			this.endTime = endTime;
			this.application = application;
		}

		static ActivityPayload copyTillEndTime(ActivityPayload payload, ZonedDateTime endTime)
		{
			return new ActivityPayload(payload.userAnonymized, payload.url, payload.startTime, endTime, payload.application);
		}

		static ActivityPayload copyFromStartTime(ActivityPayload payload, ZonedDateTime startTime)
		{
			return new ActivityPayload(payload.userAnonymized, payload.url, startTime, payload.endTime, payload.application);
		}

		static ActivityPayload createInstance(UserAnonymizedDTO userAnonymized, NetworkActivityDTO networkActivity)
		{
			ZonedDateTime startTime = networkActivity.getEventTime().orElse(ZonedDateTime.now())
					.withZoneSameInstant(ZoneId.of(userAnonymized.getTimeZoneId()));
			return new ActivityPayload(userAnonymized, Optional.of(networkActivity.getURL()), startTime, startTime,
					Optional.empty());
		}

		static ActivityPayload createInstance(UserAnonymizedDTO userAnonymized, ZonedDateTime startTime, ZonedDateTime endTime,
				String application)
		{
			ZoneId userTimeZone = ZoneId.of(userAnonymized.getTimeZoneId());
			return new ActivityPayload(userAnonymized, Optional.empty(), startTime.withZoneSameInstant(userTimeZone),
					endTime.withZoneSameInstant(userTimeZone), Optional.of(application));
		}
	}
}
