/*******************************************************************************
 * Copyright (c) 2016 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.properties;

import java.time.Duration;

public class AnalysisServiceProperties
{
	private Duration conflictInterval = Duration.ofMinutes(15);
	private Duration updateSkipWindow = Duration.ofSeconds(5);
	private Duration activityMemory = Duration.ofDays(490);

	public Duration getActivityMemory()
	{
		return activityMemory;
	}

	public void setActivityMemory(Duration activityMemory)
	{
		this.activityMemory = activityMemory;
	}

	public Duration getConflictInterval()
	{
		return conflictInterval;
	}

	public void setConflictInterval(String conflictInterval)
	{
		this.conflictInterval = Duration.parse(conflictInterval);
	}

	public Duration getUpdateSkipWindow()
	{
		return updateSkipWindow;
	}

	public void setUpdateSkipWindow(String updateSkipWindow)
	{
		this.updateSkipWindow = Duration.parse(updateSkipWindow);
	}
}
