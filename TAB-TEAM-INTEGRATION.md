# TAB team integration

JSMPCore exposes these PlaceholderAPI placeholders when PlaceholderAPI is installed:

- `%jsmp_team%` or `%jsmp_team_name%` - team name, or `None` when the player has no team.
- `%jsmp_team_tag%` - coloured `[TeamName]` tag, empty when the player has no team.
- `%jsmp_team_prefix%` - coloured `[TeamName] ` prefix, empty when the player has no team.

## TAB player list

The existing TAB config already has `tablist-name-formatting.enabled: true`. Keep that enabled.

In `plugins/TAB/groups.yml`, append `%jsmp_team_tag% ` to each existing rank's `tabprefix` value. For example, if a rank currently has:

```yaml
owner:
  tabprefix: "&c&lOWNER &r"
```

change only its prefix value to:

```yaml
owner:
  tabprefix: "&c&lOWNER &r%jsmp_team_tag% "
```

Repeat for the existing rank groups without changing their rank colours, names, or permissions. Do not replace the rank prefix with the team placeholder.

## Chat

Normal Paper chat automatically shows the coloured team tag before the player's display name. The private `/team chat` mode remains private and only sends messages to online members of that player's team.

If another chat-formatting plugin overrides Paper's chat renderer, add `%jsmp_team_tag%` to that plugin's existing chat format immediately before the player name.

JSMPCore also sets the Paper player-list name directly, so the tag works in the vanilla player list. TAB's own `groups.yml` formatting is the recommended place to combine it with existing rank prefixes.
