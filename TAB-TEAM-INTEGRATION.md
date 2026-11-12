# TAB team integration

JSMPCore exposes these PlaceholderAPI placeholders when PlaceholderAPI is installed:

- `%jsmp_team%` or `%jsmp_team_name%` - team name, or `None` when the player has no team.
- `%jsmp_team_tag%` - coloured `[TeamName]` tag, empty when the player has no team.
- `%jsmp_team_prefix%` - coloured `[TeamName] ` prefix, empty when the player has no team.

Use `%jsmp_team_prefix%` in the TAB plugin's existing tab-list name format and chat format to show the same team tag in both places. Keep the rest of your existing TAB formatting, rank prefixes, suffixes and colour codes intact; insert the team placeholder where you want the team tag to appear.

JSMPCore also sets each player's Paper player-list name directly, so the tag is visible in the vanilla player list even without TAB. Normal Paper chat messages automatically show the team tag before the player's display name. The private `/team chat` mode continues to send messages only to online members of that player's team.
