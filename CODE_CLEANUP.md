# Code Cleanup & Consistency Review

Scanned at commit `d5cc4d1`. Paths are relative to `src/main/java/me/pintoadmin/velocityServerDialog/` unless noted.

## 0. Bugs found during the scan
These go beyond cleanup, but they turned up while reviewing:
- **Mismatched permission in the same command.** `MaintenanceCommand.execute` checks `velocityserverdialog.serversdialog.maintenance` (`MaintenanceCommand.java:36`), but `suggestAsync` checks `velocityserversdialog.serversdialog.maintenance`, with an extra `s` (`:57`). As a result, tab-complete is hidden from people who are allowed to run the command.
- **Empty `[[server]]` list shows nothing.** The README and `config.toml:23` say "leave empty to list every server". `listedServers()` honours that, but the dialog is now built from `getNonGroupServers()` (`SelectionManager.java:181`), and it returns `List.of()` when the config has no servers (`:101`). So the picker is empty.
- **`group` is `null` vs `""`.** `ConfigLoader.load` stores `null` when a server has no `group` key (`ConfigLoader.java:44`). But the fallback `entry()` uses `""` (`:21`), and `getNonGroupServers()` filters on `== null` (`SelectionManager.java:103`). Pick one (preferably `""`, using `t.getString("group", "")`) and check `isBlank()`.
- **NPE on missing group keys.** `Group.name` and `Group.hover` can be `null` when the key is missing (`ConfigLoader.java:58-59`). `ServerDialogFactory.java:58` then calls `group.hover().isEmpty()`, which throws. Use defaults: `t.getString("hover", "")`.
- **Blocking ping on the netty thread.** `getOnlineServers()` blocks with `.get(1500ms)` (`SelectionManager.java:124`). `group()` calls it from the PacketEvents thread (`:249`), which stalls that player's connection for up to 1.5 s. Also, if any single ping times out, every server is reported offline (`:126-131`), including ones that already answered. Return the partial `online` map instead.
- **`back_pw` is never handled.** `PasswordDialogFactory` sends `back_pw` (`PasswordDialogFactory.java:19`), but `ClickPacketListener`'s switch has no `back_pw` case, so the Back button on the password dialog does nothing.
- **packetevents is loaded twice in dev.** packetevents is now shaded and relocated (`build.gradle.kts:15,33-34`), and `velocity-plugin.json` no longer depends on it. But `runVelocity` still downloads the packetevents plugin (`build.gradle.kts:44-46`), so the dev server loads two copies. Remove the `downloadPlugins` block.
- **PacketEvents is never terminated.** `PacketEvents.getAPI().init()` is called (`VelocityServerDialog.java:42`), but `terminate()` is never called. Add a `ProxyShutdownEvent` handler, and close the DB connection there too.
- **`ClassCastException` from the console.** `sendServersDialog` casts `(Player) source` (`SelectionManager.java:192`), which throws when the command runs from the console. Check with `instanceof Player`.

## 1. Unused code to remove

### Unused imports
Wildcard imports hide many dead imports. The notable ones:
- `MaintenanceCommand.java:3`: `import com.sun.tools.javac.*;`. This is a JDK-internal compiler package; it's unused and can break compilation.
- `ClickPacketListener.java:16`: `import java.awt.*;` (it also clashes with `java.util.List`).
- `ClickPacketListener.java:3,7,8,10,13`: `packetevents.*`, `protocol.player.*`, `protocol.sound.*`, `util.*` and `wrapper.play.server.*` appear unused.
- `DBManager.java:6,8`: `java.io.*` and `java.rmi.*`.
- `JoinListener.java:8,9,13`: `proxy.messages.*`, `proxy.server.*` and `java.util.concurrent.*`. These became unused when the ping logic moved to `SelectionManager`.
- `SelectionManager.java:14,17`: `ClickEvent` and `java.io.*`.
- `VelocityServerDialog.java:13,19`: `net.kyori.adventure.text.*` and the static `mm` import.
- `WaitingDialogFactory.java:7`: `dialog.input.*`.
- `ConfigLoader.java:6`: `java.nio.channels.*`.
- `ServerDialogFactory.java:4,17`: the explicit `Dialog` and `List` imports exist only to settle a wildcard clash. Switching to explicit imports makes them unnecessary.

### Unused fields, parameters and methods
- `ClickPacketListener.proxy` and `JoinListener.proxy` are never read.
- `SelectionManager.logger` is never read. Its only use is commented out at `:166`.
- `DBManager`:
  - `dataDir` is stored but never read.
  - The `getSavedPassword(String playerName, ...)` overload (`:49-53`) is never called. After removing it, `proxy` is unused too.
  - `Class.forName("org.sqlite.JDBC")` (`:26`) isn't needed with JDBC 4 drivers plus `mergeServiceFiles()`.
- `SelectionManager.passwdPendings` (`:60`) and the loop that fills it (`:170-179`): a password dialog and `Pending` are built for every password server on every join, and never read. Delete both.
- `SelectionManager.resend(p)` (`:35-38`) is the same as `showDialog(p.event().getPlayer(), p.dialog())`. Remove it.
- `ServerDialogFactory.build(...)` takes a whole `SelectionManager` only to read `playersPostjoin`. Pass `boolean postJoin`.
- `MaintenanceCommand.dataDir` and the `Path dataDir` parameter on `ConfigLoader.setMaintenance` (`:81`) duplicate the static `ConfigLoader.dataDir`. Keep one source of truth.
- The `isEmpty()` early returns in `listedGroups()` (`:110`), `getGroupServers()` (`:92`) and `getNonGroupServers()` (`:101`) are redundant, because streaming an empty list already gives an empty list.

### Dead or commented-out code
- `ClickPacketListener.java:39`: `/*postjoin ? new WrapperPlayClientCustomClickAction(event) : */`.
- `SelectionManager.java:166`: the commented-out `logger.warn(...)`.
- `ServerDialogFactory.java:67-68`: the commented-out group `showPermString` check. `Group` has no such field, so either implement `show-permission` for groups or delete the comment.
- `SelectionManager.java:157`: a no-op task is scheduled just to have a non-null `ScheduledTask`. Make `timeoutTask` nullable instead.
- `ClickPacketListener.java:105-106`: the empty `default -> {}` branch.
- `ServerDialogFactory.java:85-89`: collapse the nested `if`s into a single condition.

## 2. Consistency

### Imports
- Wildcard and explicit imports are mixed, often in the same file (`VelocityServerDialog.java:3-14`, `SelectionManager.java:3-15`). Run "Optimize Imports" and use explicit imports.
- The static import of `Text` is `Text.*` in some files (`JoinListener`, `NoticeDialogFactory`, `ServerDialogFactory`, `WaitingDialogFactory`) and `Text.mm` in others. Use `Text.mm` everywhere.

### Formatting
Pick one style and let IntelliJ "Reformat Code" apply it:
- `if(` vs `if (`. For example, `SelectionManager.java:153,154,158,168,221,223` and `ClickPacketListener.java:78,93`.
- `){` vs `) {`. For example, `ConfigLoader.java:14,35,42,56`, `JoinListener.java:45`, `NoticeDialogFactory.java:26`, and most `SelectionManager` methods.
- Alignment padding in constants: `BACK   =` and `QUIT   =` (`NoticeDialogFactory.java:17`, `ServerDialogFactory.java:27-28`, `WaitingDialogFactory.java:18`).
- Trailing whitespace at `SelectionManager.java:244`.
- Missing trailing newlines in `ClickPacketListener`, `ConfigLoader`, `JoinListener`, `SelectionManager` and `config.toml`.
- `ConfigLoader.java:12-14`: the record components are indented inconsistently.
- `NoticeDialogFactory.java:26-29`: the two `build` overloads have no blank line between them, and the Javadoc sits on the `String` overload only.

### Record accessor use
Records are accessed through both fields and accessors:
- Field access:
  - `p.event.getPlayer()` (`SelectionManager.java:37,148,153,285,321`).
  - `p.timeoutTask.cancel()` (`:322`).
  - `g.id` (`ConfigLoader.java:27`).
- Accessors: `p.event().getPlayer()` and `p.timeoutTask().cancel()` elsewhere.

Use the accessors everywhere.

### Naming
- Typos:
  - "mantinence" (`config.toml:11`, which is also the permission name).
  - "What do do" (`config.toml:4`).
  - "Teturns" (`SelectionManager.java:81`).
  - "Signifiesd" (`SelectionManager.java:315`).
  - "occured" (`MaintenanceCommand.java:45`).
- Server-list methods mix prefixes: `listedServers()` / `listedGroups()` vs `getGroupServers()` / `getNonGroupServers()` / `getOnlineServers()`. Pick one convention.
- `passwd`, `pw`, `pwString` and `password` are all used for the same thing. Likewise:
  - `permString` / `showPermString` vs the `permission` / `show-permission` config keys.
  - `hover` (group) vs `description` (server) for the same tooltip concept.

  Suggested names: `Entry.password()`, `Entry.permission()`, `Entry.showPermission()`, `checkPassword()`, and the actions `password` / `password_submit`.
- `ConfigLoader` is a record holding config values. `Config` or `PluginConfig` would be clearer.
- `MaintenanceCommand` also opens the dialog (no args). Consider `ServersDialogCommand`.
- `JoinListener.onInitialServerFailure` returns early when the player is *not* post-join, so it really handles post-join kicks. Rename it to match.
- `playersPostjoin` / `postjoin`: use `postJoin`.
- `WaitingDialogFactory.java:23`: the variable is called `back`, but it's the Disconnect (quit) button.
- Factory parameters are named `cfg` in some places and `config` in others.

### Keys for player data
- `playersPostjoin` is keyed by `Player`, while `pending` and the DB use `UUID`. Key it by `UUID`.
- It's a plain `HashMap` written from the netty thread (`ClickPacketListener:37`), event threads (`JoinListener:38,59,66`) and the scheduler. Use `ConcurrentHashMap`.
- It's `public` and read directly by `ServerDialogFactory` and `JoinListener`. Make it private and expose `isPostJoin(UUID)` / `setPostJoin(...)`.
- `.get(player)` auto-unboxes and throws an NPE when the key is missing (`JoinListener.java:46`, `SelectionManager.java:37,46,56,158,221`). Use `getOrDefault(..., false)`.

### Error handling and logging
Three styles are in use. The `DBManager` form is correct; use it everywhere:
- `e.printStackTrace()` (`ClickPacketListener.java:96`, `ServerDialogFactory.java:52`).
- `logger.error(e.getStackTrace().toString())` (`MaintenanceCommand.java:47`). This logs an array reference, not the trace.
- `logger.error("msg", e)` (`DBManager`). This is the correct form.

Also: `ClickPacketListener.java:76-83` throws a dummy `SQLException` for control flow. Use `if/else`, and catch only real DB errors.

### Duplicated logic to consolidate
- **Client brand parsing**: duplicated at `SelectionManager.java:167-168` and `:242-243`. Extract `brandOf(Player)`.
- **"Could not connect to server" notice**: duplicated at `JoinListener.java:50-54` and `SelectionManager.java:224-228`. Extract `NoticeDialogFactory.connectionFailed(Optional<Component>)`.
- **Config entry → `RegisteredServer` stream**: `listedServers`, `getGroupServers` and `getNonGroupServers` (`SelectionManager.java:83-107`) repeat `map(proxy.getServer).flatMap(Optional::stream)`. Use one private helper that takes a predicate.
- **Entry lookup by name**: `ConfigLoader.entry()`, `SelectionManager.checkPasswd()` (`:296`) and the loop in `ConfigLoader.setMaintenance()` (`:86-92`) all do it. Reuse `entry()`, or add `Optional<Entry> find(String)`.
- **Registered server lookup by name**: `SelectionManager.java:149-151` and `:205-207` both do it. Extract a helper.
- **`ResourceLocation` constants**: these are spread across four factories, and `QUIT` is defined twice (`ServerDialogFactory`, `WaitingDialogFactory`). Centralize them in one `Actions` class next to `NAMESPACE`, and use them in the `ClickPacketListener` switch instead of string literals.
- **Payload extraction**: `ClickPacketListener` repeats `if (packet.getPayload() instanceof NBTCompound tag) { ... getStringTagValueOrNull("server") ... }` in five cases. Extract it once before the `switch`.
- **Red "Back" button and empty `CommonDialogData`**: these are duplicated in `NoticeDialogFactory`, `PasswordDialogFactory` and `WaitingDialogFactory`. Add a shared helper.
- **Empty body**: `mm("")` (`NoticeDialogFactory.java:39`, `WaitingDialogFactory.java:32`) creates an empty plain-message body. Use `List.of()`.
- **Repeated calls in `begin()`**: it calls `listedServers()` several times (directly at `:149`, and through `getOnlineServers()` twice at `:118,128`). Compute it once and pass it along.

### Config handling
- `ServerDialogFactory.java:49-53` reloads `config.toml` from disk every time the dialog opens. Every other class keeps the startup `ConfigLoader`. As a result, `maintenance` is fresh in the dialog but stale for `MaintenanceCommand` tab-complete and `SelectionManager.checkPasswd` / `getGroupServers` / `listedServers`. Use a single config holder that `setMaintenance` (and a reload command) refreshes.
- `ConfigLoader.setMaintenance` calls `load(dataDir)`, which also overwrites the static `dataDir`. A static mutable field on a record is surprising. Make it a component, or move file I/O into a separate class.
- The `timeout-seconds` comment says it's clamped to `5..25` (`config.toml:2`), but the code clamps to `2..25` (`ConfigLoader.java:63`).
- Code defaults don't match the bundled config. For example, `save-passwords` defaults to `false` in code but is `true` in `config.toml`.
- Comment placement is inconsistent: `on-timeout` has a trailing comment (`config.toml:4`), while `legacy-clients` has a comment on the line above (`:5`). These two keys have the same `"default" | "kick"` shape, so document them the same way.
- The body hard-codes "15 seconds" (`config.toml:15`) instead of following `timeout-seconds`. Use a `<timeout>` placeholder.
- Player-facing strings are hard-coded in Java while the rest live in config. Examples:
  - "You timed out! Select a server." (`SelectionManager.java:153`), alongside the configurable `kick-quit-message`.
  - "Could not connect to server" / "Unknown reason".
  - "Wrong password!", "The X server is locked" and "…under maintenance".
  - "Connecting to…".
  - "Back", "Submit", "Disconnect", "Close", "Enter Password", "(locked)", "(maintenance)", "(offline)".

  Move them to config.
- `ServerDialogFactory.java:85`: `Objects.equals(entry.type(), "vanilla")` is case-sensitive, while every other comparison uses `equalsIgnoreCase`.
- Server entries support `show-permission`, but groups don't (see the commented-out code at `ServerDialogFactory.java:67-68`). This is a feature gap between two parallel concepts.

## 3. Resource handling and smaller fixes
- `DBManager`:
  - `createTable()` (`:45`) and `getSavedPassword()` (`:57-61`) leak their `Statement`, `PreparedStatement` and `ResultSet`. Use try-with-resources, as `addSavedPassword` already does.
  - `while (rs.next()) { return ...; }` (`:63`) should be an `if`.
  - Only `addSavedPassword` is `synchronized`, but every method shares one `Connection`. Make this consistent.
  - The update-then-insert pair can be one `INSERT ... ON CONFLICT(uuid, server) DO UPDATE` with `UNIQUE(uuid, server)`.
  - Passwords are stored in plaintext; store a hash.
- `MaintenanceCommand.suggestAsync` (`:61`): `e -> e.velocity()` can be `ConfigLoader.Entry::velocity`.
- `MaintenanceCommand` is silent when the permission check fails (`:33,36`). Send a message, or override `hasPermission()` so Velocity hides the command.
- `SelectionManager.begin` timeout (`:149-154`): when `kickOnTimeout` is set, it still looks up and sets the default server before disconnecting. Use an `if/else` so it does one or the other.
- `JoinListener.onInitialServerFailure` shows a notice but never sets the `KickedFromServerEvent` result. Velocity's default handling (redirect or disconnect) still runs, which may close the dialog right away. Set the result explicitly, e.g. `Notify`.
- `VelocityServerDialog`: `proxy`, `container` and `logger` use constructor injection, but `dataDir` uses field injection. Inject everything through the constructor.
- `build.gradle.kts`: `java-library` is only needed to publish an API. `java` is enough for a plugin jar.

## Suggested order
1. Fix the bugs in section 0 (permission typo, empty-server fallback, `null` group/hover, the `back_pw` handler, the blocking ping, and the duplicate packetevents in `runVelocity`).
2. Run "Optimize Imports" and "Reformat Code" on all files.
3. Delete the dead code: `passwdPendings`, `resend`, the unused overload and fields, and the commented-out blocks.
4. Fix logging and resource leaks, and add a shutdown handler (DB plus PacketEvents).
5. Consolidate duplicated logic and centralize the action ids.
6. Redesign config handling: one holder, and move strings to config.
