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
- **Problems** (Agreements for teens): one tap shows the consequence agreed in advance and
  applies it.
- **Rewards** (Privileges for teens): bought with what was earned; one of them is the goal the
  child is saving for.
- **Show to the child**: a full-screen page of their day, their stars and their goal. Leaving
  it needs a long press, and the parent code if one is set.
- **History** of everything, and backups to a file you keep.

## The fairness rules (the same for every child)

- Stars earned are never taken away: a problem brings its agreed consequence, not a loss of
  effort.
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
