delete from message_destinations_messages;
update messages set sender_copy_message_id = null;
delete from messages;
delete from message_sources;
delete from activities;
delete from interval_activity_spread;
delete from interval_activities where dtype = 'DayActivity';
delete from interval_activities;
update goals set previous_instance_of_this_goal_id = null;
delete from time_zone_goal_spread_cells;
delete from time_zone_goal_zones;
delete from goals;
delete from users_private_buddies;
delete from buddies;
delete from users_anonymized_buddies_anonymized;
delete from buddies_anonymized;
delete from users_anonymized;
delete from users;
delete from users_private;
delete from confirmation_codes;
delete from message_destinations;
delete from new_device_requests;
