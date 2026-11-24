# StarSteps — notice the good, keep consequences fair

An Android app for parents to track their children's behaviour: stars or points for good
moments, rewards bought with them, and calm, agreed consequences for problems. Everything
adapts to each child's age. English and French. Nothing leaves the phone.

## How it works

- **Add a child** with their birth month and year. The age sets up the rest:

  | Age | Counts in | Suggested | Consequences | Children's screen |
  |---|---|---|---|---|
  | 2–5 | stars, drawn one by one | teeth, toys, dressing, kindness… | short calm-down time | big pictures and stars |
  | 6–8 | stars | homework, room, reading, helping… | a little less screen time | stars, goal, streak |
  | 9–12 | points | homework, chores, sport, screens off… | evening without games, a privilege | points, streak, week chart |
  | 13+ | points | on time, chores, respect… | "agreements": earlier curfew, phone handed in | calm, grown-up page |

  Everything suggested can be changed, removed or added to (hold an item).
- **Well done**: one tap adds the stars (with Undo for a slip of the finger). 🎉 Bonus for a
  special effort.
- **Not well done**: one tap takes points off (smaller amounts than the good ones, also with
  Undo). The balance can go below zero; rewards are bought only from a positive balance.
- **Problems** (Agreements for teens): one tap shows the consequence agreed in advance and
  applies it.
- **Rewards** (Privileges for teens): bought with what was earned; one of them is the goal the
  child is saving for.
- **Show to the child**: a full-screen page of their day, their stars and their goal. Leaving
  it needs a long press, and the parent code if one is set.
- **The rocket** (on the children's page): good moments give paint, and the child
  taps the rocket's eight parts to colour them; the whole rocket at the day's target, and then
  it blasts off. The colour is the day's score: green (no problems, or 3 good moments or more
  for each), orange (a mixed day), red (more problems and "not well done" than good moments). A blank rocket every
  morning.
- **This week** on each child's page: points, good moments and problems against the week
  before, the best day, the behaviour noticed most.
- **Evening reminder** (19:00 by default, or off, in Settings): a notification naming the
  children with nothing noted yet that day.
- **History** of everything, and backups to a file you keep.

## The fairness rules (the same for every child)

- Points come off only through the "not well done" items you choose; a problem brings its
  agreed consequence and takes nothing.
- Consequences last the day; every day starts fresh.
- When problems logged outnumber good moments noticed (fewer than 3 for 1 over a week), the app
  says so gently: noticing the good changes behaviour more than consequences do.

## Build

```bash
cd StarSteps
./gradlew testDebugUnitTest     # the rules' tests
./gradlew assembleRelease       # the APK to install
```

Every push that touches `StarSteps/` runs the *StarSteps Android build* workflow: tests, the
APK, and the app driven on an emulator with screenshots of every screen. Requires Android 10+.
