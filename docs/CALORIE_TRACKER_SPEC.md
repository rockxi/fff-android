# Calorie Tracker: product and UX specification

Status: implementation contract for the first local-first release.

Research date: 2026-09-12.

## Product intent

Calorie Tracker is a new application inside the FFF launcher. It is a fast,
private food diary for one owner. The primary job is to answer three questions
without leaving today's screen:

1. How many calories have I eaten today?
2. How many calories remain against my daily target?
3. Am I close to my protein, carbohydrate and fat targets?

The MVP deliberately optimizes for manual entries that take only a few taps. It
does not depend on Telegram, Harness, a remote food database or an account. Its
source of truth is a separate Room/SQLite database in application-private
storage.

## Comparable-product research

Only first-party product/help material was used. The products are references for
interaction patterns, not visual templates.

| Product | Officially documented pattern | Decision for FFF |
| --- | --- | --- |
| MyFitnessPal | The Today tab is the initial daily diary; consumed and remaining calories plus macros are prominent; entries are grouped by meal; users can navigate to another date. Quick Add records known calories without creating a full food, and Quick Log adds a search result with its current portion in one tap. | Open on today, keep the daily calorie summary above meal groups, offer previous/next date navigation and make reuse/quick entry the shortest flows. |
| YAZIO | The Diary is the central screen. Its dashboard shows consumed, remaining and burned calories and macro intake; meals are diary sections. Quick Add accepts a description, calories and optional macros. Saved meals speed up repeated combinations. | Use familiar Breakfast/Lunch/Dinner/Snacks sections and permit a lightweight entry with name, kcal and optional macros. Repeated foods must be available without retyping nutrition. Activity/burned calories are intentionally deferred. |
| Cronometer | The Diary exposes an energy summary and target bars for macronutrients. The user can switch the display between consumed and remaining values and inspect detailed target progress. | Show compact calorie progress and three macro bars on the day screen; keep the MVP to energy and protein/carbohydrate/fat rather than exposing micronutrient complexity. |

Official sources:

- [MyFitnessPal: Your Today tab](https://support.myfitnesspal.com/hc/en-us/articles/39985611667341-Your-Today-tab)
- [MyFitnessPal: What is Quick Add?](https://support.myfitnesspal.com/hc/en-us/articles/360032621971-What-is-Quick-Add)
- [MyFitnessPal: Quick Log](https://support.myfitnesspal.com/hc/en-us/articles/360032622491-Quick-Log)
- [YAZIO: Tutorial of the YAZIO app](https://help.yazio.com/hc/en-us/articles/11804776635281-Tutorial-of-the-YAZIO-app)
- [YAZIO: What are remaining calories?](https://help.yazio.com/hc/en-us/articles/360000936649-What-are-remaining-calories-and-do-I-need-to-consume-them)
- [YAZIO: Quick Add a meal](https://help.yazio.com/hc/en-us/articles/360000530018-Can-I-log-my-meal-calories-without-individual-ingredients)
- [YAZIO: What are meals?](https://help.yazio.com/hc/en-us/articles/360000620798-What-are-meals)
- [Cronometer: Mobile Macronutrient Breakdown](https://support.cronometer.com/hc/en-us/articles/32659895319444-Mobile-Macronutrient-Breakdown)

## MVP scope

The first release includes:

- a Calorie Tracker tile in the FFF application catalog;
- a today-first diary with previous day, next day and explicit date selection;
- daily targets for calories, protein, carbohydrates and fat;
- four fixed meal sections: `Завтрак`, `Обед`, `Ужин`, `Перекусы`;
- a reusable personal food catalog;
- manual food creation with nutrition per 100 g;
- adding a food to a meal by entering a positive gram amount;
- quick entry with a name, calories and optional macros for the consumed portion;
- editing and explicit deletion of diary entries;
- daily consumed and remaining calorie values;
- consumed/target progress for protein, carbohydrates and fat;
- recent-food shortcuts, ordered by latest use;
- all data persisted locally and retained by in-place application updates.

The MVP does not include barcode scanning, camera/AI recognition, recipes or
multi-food meals, exercise/burned-calorie adjustments, micronutrients, water,
weight, cloud sync, sharing, health-platform integration or assistant access.
These exclusions keep the diary accurate without pretending to have an external
nutrition database or reliable activity model.

## Navigation and screens

### FFF catalog

Add `Калории` as a first-class application tile alongside Finance, Harness,
Remote Control and Gym Tracker. Its description should promise a local food
diary, calories and macros. Opening it goes directly to the diary for the
device's current local date.

### Daily diary (default screen)

From top to bottom:

1. Back action, title `Калории` and settings action.
2. Date row with previous/next arrows, tappable `Сегодня` or formatted date, and
   a custom calendar date picker.
3. Calorie summary card: target, consumed and remaining. Progress is clamped
   visually but the numeric remainder may be negative and is then labelled
   `Сверх цели` by absolute value.
4. Three compact macro progress bars: `Белки`, `Жиры`, `Углеводы`, each showing
   consumed grams and target grams.
5. The four meal sections. Each header shows meal calories and an add action;
   each row shows food name, grams/portion and calories. Tapping a row edits it.
6. An empty meal shows a small helpful prompt rather than a large blank card.

The diary uses one vertically scrolling surface. Summary information remains
compact so at least the first meal section is visible on a common phone viewport.
Changing dates must not create empty database rows.

### Add to meal

Opening a meal's add action shows a custom FFF modal/screen with:

- a search field focused immediately;
- recent foods before text is entered;
- locally filtered food results as the user types;
- `Быстрая запись` and `Создать продукт` actions;
- one-tap selection of a result, followed by the amount editor.

Selecting a catalog food opens an amount modal. The gram field receives numeric
focus immediately, defaults to the most recently used amount for that food when
available (otherwise `100`), previews calculated calories/macros, and has one
clear `Добавить` action.

### Quick entry

Quick entry is for already-known totals. Fields:

- name (required, 1–120 trimmed characters);
- calories for this consumed portion (required positive whole kcal);
- protein, fat and carbohydrates in grams (optional, non-negative decimals);
- meal and date inherited from the entry point but visibly editable.

Saving creates a diary entry that does not create a catalog food. This distinction
prevents ambiguous per-100-g nutrition from being inferred from portion totals.

### Personal foods

Accessible from diary settings and the add flow. The list supports search,
create, edit and explicit delete. A food contains a name and calories/macros per
100 g. Foods referenced by diary history cannot be hard-deleted; the UI explains
that its diary entries must be removed first. Editing a food changes future
calculations and must not rewrite nutrition already recorded in history.

### Targets/settings

A custom FFF form edits the four daily defaults. Calories must be positive;
macro targets must be non-negative. Defaults apply to every date, including
history display, until changed. Per-day target overrides are not part of MVP.

## Interaction and accessibility contract

- Use the existing custom FFF modal components, not platform-styled
  `AlertDialog` surfaces.
- Primary touch targets are at least 48 dp and expose meaningful semantics.
- Numeric fields request an appropriate decimal/numeric keyboard and select
  their useful value on focus.
- Saving is disabled while invalid or already in flight; repeated taps cannot
  create duplicate entries.
- Destructive actions require explicit confirmation and identify the object.
- Back closes the active modal before leaving Calorie Tracker.
- Empty, loading and recoverable error states have visible text, not color alone.
- Macro and calorie states remain understandable to screen readers; progress
  semantics include the label, consumed value and target.

## Data model

Use a dedicated `fff-calorie.db`; do not add these tables to Finance or Gym.
Store local dates as ISO `YYYY-MM-DD`. Store energy as integer kcal and macro
weights as integer milligrams to avoid floating-point drift.

### `CalorieProfileEntity`

- singleton `id = 1`;
- `dailyCaloriesKcal: Int`;
- `proteinTargetMg: Long`;
- `fatTargetMg: Long`;
- `carbTargetMg: Long`;
- `updatedAt: Long` epoch milliseconds.

Seed a usable but clearly editable profile: 2000 kcal, 120 g protein, 70 g fat
and 230 g carbohydrates. These are product defaults, not medical advice.

### `FoodEntity`

- `id: Long` primary key;
- `name: String`;
- `caloriesPer100gKcal: Int`;
- `proteinPer100gMg: Long`;
- `fatPer100gMg: Long`;
- `carbPer100gMg: Long`;
- `createdAt: Long`;
- `updatedAt: Long`.

Names need not be globally unique. Nutrition values are non-negative and at
least calories or one macro must be non-zero.

### `DiaryEntryEntity`

- `id: Long` primary key;
- `localDate: String` indexed;
- `mealType: BREAKFAST | LUNCH | DINNER | SNACK`;
- nullable `foodId: Long` with restrictive foreign key;
- `displayNameSnapshot: String`;
- `amountGramsMg: Long?` for catalog-food entries;
- `caloriesKcal: Int` snapshot for the consumed portion;
- `proteinMg: Long`, `fatMg: Long`, `carbMg: Long` snapshots;
- `createdAt: Long` and `updatedAt: Long`.

Catalog entries require a positive amount and a present `foodId`. Quick entries
require both to be null. Snapshot nutrition is authoritative for diary totals, so
editing a catalog food never mutates historical days.

For a catalog food, calculate each consumed value with integer half-up rounding:
`round(per100gValue * amountMg / 100_000)`, because 100 g is 100,000 mg. Guard multiplication overflow
and reject an amount whose computed values exceed storage/UI bounds.

### Recent use

Recent foods are derived from diary entries with a non-null `foodId`, grouped by
food and ordered by maximum `createdAt` descending. No additional table is
required for MVP. The latest entry supplies the default gram amount.

## Domain rules and limits

- All writes that affect multiple rows are Room transactions.
- Dates are interpreted in the device's current timezone at the moment the user
  selects or creates an entry. Existing ISO dates never shift after timezone
  changes.
- User-facing amounts accept up to three decimal grams but are stored exactly as
  milligrams. Calories are shown as whole kcal; macros are shown to one decimal
  gram.
- Entry amount is greater than zero and at most 100,000 g.
- Per-entry calories are 0–1,000,000 kcal after calculation; a quick entry must
  have calories greater than zero.
- Macro values are non-negative and individually bounded to 100,000 g per entry.
- Text is trimmed, bounded and never used as SQL or formatted markup.
- A failed create/edit/delete leaves the existing diary and totals unchanged.
- Daily aggregation includes all four meal types and uses checked `Long` sums;
  impossible overflow is surfaced as an error rather than wrapped.
- Database migrations must be lossless and covered by migration tests once a
  version beyond schema 1 exists.
- Calorie data is outside the versioned Finance backup. Android platform backup
  remains disabled, so MVP data preservation relies on in-place updates. A
  dedicated export/restore is a future feature and this limitation must be
  visible in product documentation.

## Visual direction

The module inherits FFF's dark, lightweight cyberpunk language but has a distinct
nutrition accent:

- near-black background and graphite cards;
- fresh lime/mint primary accent for positive progress and actions;
- protein in muted coral, fat in warm amber, carbohydrates in cyan;
- thin borders, restrained glow only on the primary progress ring/action;
- no red/green-only meaning: labels and values always accompany color;
- compact typography and spacing consistent with existing Finance and Gym phone
  layouts;
- short 150–220 ms transitions using alpha/translation only, respecting the
  system reduced-motion setting.

The design should feel like another FFF application, not an embedded third-party
tracker. Do not copy competitor colors, icons, screenshots or layouts.

## Acceptance scenarios for implementation

1. Fresh install opens today's empty diary and shows seeded targets.
2. Create `Творог` with 120 kcal, 18 g protein, 5 g fat and 3 g carbohydrates
   per 100 g. Add 180 g to breakfast and verify the exact oracle: 216 kcal,
   32.4 g protein, 9.0 g fat and 5.4 g carbohydrates in the preview, meal total
   and daily totals.
3. Reopen the add flow: `Творог` appears in recents and 180 g is prefilled.
4. Add a quick lunch entry with calories and partial macros; it contributes to
   the correct meal and day without appearing in Personal foods.
5. Navigate to yesterday, add/edit/delete an entry, return to today, and verify
   the days remain independent.
6. Edit a catalog food after logging it and verify the historical entry retains
   its snapshot totals while a new entry uses the changed nutrition.
7. Try invalid/oversized/rapid duplicate submissions and verify no partial or
   duplicate entry is persisted.
8. Rotate/relaunch the app and verify the selected day data and all targets are
   still in SQLite.
9. Verify narrow-phone layout, keyboard focus, screen-reader labels and 48 dp
   touch targets through the user-facing Compose UI.

## Verification expectation

This specification is a documentation-only research deliverable, so developer
unit tests are not applicable to this file itself: it has no executable behavior.
Implementation tasks derived from it must add repository/ViewModel unit tests,
Room schema/export and migration coverage where applicable, Compose semantics
coverage for critical interactions, and pass the repository's complete Gradle
gate before release.
