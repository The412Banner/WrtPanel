# Porting a LuCI view to WrtPanel

Every page in WrtPanel is a port of one LuCI view (`htdocs/luci-static/resources/view/**.js`)
or template. The goal is 1:1: same sections, tabs, options, option names, labels, help texts,
defaults, datatypes, dependencies and the same backend calls. Visual layout is native
Material 3, not a copy of LuCI's HTML.

Reference sources: openwrt/luci @ `1c75ea46c68ebc386fef9922089260de16f30417` (the version on the
test router and the user's router).

## Where things live

| What | File |
|---|---|
| ubus / login / cgi-io client | `core/LuciClient.kt` (`ctx.call`, `ctx.callOrNull`, `client.batch`) |
| `file` object + cgi-exec / cgi-download (fs.js) | `core/Fs.kt` (`ctx.fs.read/write/list/stat/exec/execDirect/readDirect/remove`) |
| UCI state (uci.js) | `core/Uci.kt` (`ctx.uci.load/get/set/unset/add/remove/sections/move/save`) |
| Save & Apply, rollback, revert (ui.changes) | `core/Changes.kt` (`ctx.session.changes`) |
| form.js model | `form/Form.kt` |
| form.js rules (depends, parse, save) | `form/FormRuntime.kt` |
| form.js rendering + `FormPage` | `form/FormUi.kt` |
| validation.js | `form/Validation.kt` |
| tools/widgets.js (ZoneSelect, NetworkSelect, DeviceSelect, …) | `form/Widgets.kt` |
| network.js helpers (maskToPrefix, protocols, rt_tables) | `pages/common/NetTools.kt` |
| UI building blocks (cards, tables, badges, code, polling) | `ui/components/Components.kt` |
| Page registry | `pages/Pages.kt` + `pages/<Group>Pages.kt` |

## Form views (form.Map based)

```kotlin
@Composable
fun RoutesPage(ctx: PageContext) = FormPage(ctx) {          // receiver: PageContext
    // view.load(): fetch whatever render() needs
    val rtTables = NetTools.parseRtTables(fs.readOrNull("/etc/iproute2/rt_tables") ?: "")

    val m = FormMap("network", "Routing", "description html")  // new form.Map(config, title, descr)
    m.tabbed = true                                         // m.tabbed = true
    m.grid("route", "Static IPv4 Routes") {                 // m.section(form.GridSection, 'route', …)
        anonymous = true; addremove = true; sortable = true
        tab("general", "General Settings")                  // s.tab(...)
        value("target", "Target", "Network address", tab = "general") {   // s.taboption('general', form.Value, …)
            rmempty = false; datatype = "cidr4"; placeholder = "0.0.0.0/0"
            cfgvalueFn = { rt, sid -> … }                   // o.cfgvalue = function(section_id) {…}
            writeFn = { rt, sid, v -> … }                   // o.write = …
        }
        flag("disabled", "Disable") { modalonly = false; editable = true }
    }
    m                                                       // return the map
}
```

Mapping:

| LuCI | WrtPanel |
|---|---|
| `form.TypedSection` | `m.typed(type, title, descr) { }` |
| `form.NamedSection` | `m.named(name, type, title, descr) { }` |
| `form.GridSection` | `m.grid(type, …)` (rows + full-screen editor) |
| `form.TableSection` | `m.table(type, …)` |
| `form.Value` | `value(name, title, descr, tab)` — with `value(k, label)` choices it becomes a combobox |
| `form.ListValue` | `list(...)`; `widget = "radio"` for radio |
| `form.RichListValue` | `list(...)` + `value(k, label, description)` |
| `form.Flag` | `flag(...)` (`enabled`/`disabled` strings, default = disabled) |
| `form.MultiValue` | `multi(...)` |
| `form.DynamicList` | `dynlist(...)` |
| `form.TextValue` | `text(...)` (`rows`, `monospace`) |
| `form.DummyValue` | `dummy(...)` (`textvalueFn` / `render`) |
| `form.Button` | `button(...)` (`inputtitle`, `inputstyle`, `onClick`) |
| `form.HiddenValue` | `hidden(...)` |
| `form.FileUpload` | `file(...)` (`rootDirectory`) |
| `form.SectionValue` | `s.nested(kind, type) { }` + `subsection(name, sub, title)` |
| `o.depends('x', 'y')` | `depends("x", "y")`; object form `depends("a" to "1", "b" to "2")` |
| `o.depends({ x: /re/ })` | `depends("x", Regex("re"))` |
| `o.depends({ '!reverse': true, x: 'y' })` | `dependsNot("x" to "y")` |
| `o.depends({ '!contains': true, x: 'y' })` | `dependsContains("x", "y")` |
| `o.validate = function(sid, v) { return true or 'msg' }` | `validate = { rt, sid, v -> null or "msg" }` |
| `o.load = function(sid) { return … }` | `cfgvalueFn` |
| `o.load` that returns a Promise (fetch) | `loadFn = { rt -> … }` (runs during page load) |
| `o.textvalue` | `textvalueFn` |
| `o.renderWidget` (custom DOM) | `render = { rt, sid -> /* Composable */ }` |
| `o.onchange` | `onChange = { rt, sid, v -> }` |
| `s.filter = function(sid) {}` | `filter = { sid, section -> Boolean }` |
| `s.sectiontitle` | `sectionTitle = { sid -> … }` |
| `s.handleAdd` overrides that set defaults | `onAdd = { rt, sid -> rt.uci.set(...) }` |
| `m.chain('luci')` | `m.chain("luci")` |
| `widgets.ZoneSelect / NetworkSelect / DeviceSelect` | `zoneSelect(...) / networkSelect(...) / deviceSelect(...)` |
| `L.hasSystemFeature('x')` | `session.hasFeature("x")` |
| `uci.get('c', 's', 'o')` | `uci.get` / `uci.getString` / `uci.getList` |
| `L.toArray(x)` | `UciStore.toArray(x)` |
| view `handleSave` extra work | `m.beforeStage` / `m.afterSave` |
| `handleSaveApply: null` etc. | `m.hideSaveApply / hideSave / hideReset = true` |

`FormPage` unloads UCI and rebuilds the map on every open and after every apply/revert, like a
LuCI page load.

## Custom views (no form.Map, or a form plus status tables)

Write a normal `@Composable fun XyzPage(ctx: PageContext)` using `PageColumn`, `PageHeader`,
`SectionCard`, `InfoTable`, `ResponsiveTable`, `UsageBar`, `StatusBadge`, `CodeBlock`,
`rememberPolled(intervalMs = …) { … }` (LuCI `poll.add`, interval = `ctx.client.env?.pollInterval`).
Actions call the same ubus / fs methods as the view. Confirmations: `ctx.dialogs.ask(...)`.
Messages: `ctx.toast(...)`. For pages that mix forms and custom parts use
`FormView(rt, onReset, extraTop, extraBottom)` or `render` on options.

## Rules

- Copy option names, labels, descriptions and placeholders exactly from the LuCI source
  (HTML tags in descriptions are fine, they are rendered). No extra explanatory text.
- Same backend calls (object, method, params) as the view; same `fs.exec` commands.
- Register the page in `pages/<Group>Pages.kt` by its menu action path, e.g.
  `r.view("network/routes") { RoutesPage(it) }` (path = `action.path` from `admin/menu`).
- Never test writes against the user's router; use the local test router (see PROGRESS_LOG.md).
- Do not change files under `core/`, `form/` or `ui/` for one page; if the engine misses
  something, add a small, general feature and note it in the commit message.
