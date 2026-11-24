package com.starsteps.app.core

/**
 * What changes with the child's age. Everything the app shows and suggests follows from the
 * group: the words, the size of the numbers, the kinds of rewards and consequences, and how the
 * children's screen looks.
 */
enum class AgeGroup(
    val from: Int,
    /** Stars (young children) or points. */
    val unit: Unit,
    /** Numbers are shown; for the youngest, stars are drawn one by one instead. */
    val numbers: Boolean,
    /** Stars a good day usually brings: the daily target shown to the child. */
    val dailyTarget: Int,
    /** The most one behaviour is worth (keeps values in proportion for the age). */
    val maxValue: Int,
    /** How the children's screen looks. */
    val screen: Screen,
) {
    /** 2–5: pictures and stars, few things at a time, rewards soon after, only calm-down time. */
    LITTLE(0, Unit.STARS, numbers = false, dailyTarget = 5, maxValue = 3, screen = Screen.PLAYFUL),
    /** 6–8: stars, a reward shop, light consequences. */
    CHILD(6, Unit.STARS, numbers = true, dailyTarget = 8, maxValue = 5, screen = Screen.PLAYFUL),
    /** 9–12: points, saving for bigger rewards, weekly goals and streaks. */
    PRETEEN(9, Unit.POINTS, numbers = true, dailyTarget = 15, maxValue = 10, screen = Screen.SPORTY),
    /** 13+: points towards privileges, agreements rather than rules, a calm, grown-up screen. */
    TEEN(13, Unit.POINTS, numbers = true, dailyTarget = 20, maxValue = 20, screen = Screen.CALM);

    enum class Unit { STARS, POINTS }
    enum class Screen { PLAYFUL, SPORTY, CALM }

    companion object {
        fun of(age: Int): AgeGroup = values().last { age >= it.from }
    }
}

/**
 * The behaviours, rewards and consequences suggested when a child is added, by age. [key]
 * names the text (in the app's languages); parents change or remove anything afterwards.
 */
object Presets {
    class Good(val key: String, val emoji: String, val value: Int)
    class Challenge(val key: String, val emoji: String, val consequenceKey: String)
    class NotGood(val key: String, val emoji: String, val value: Int)
    class Prize(val key: String, val emoji: String, val cost: Int)

    fun good(g: AgeGroup): List<Good> = when (g) {
        AgeGroup.LITTLE -> listOf(
            Good("teeth", "🪥", 1), Good("tidy_toys", "🧸", 1), Good("dressed", "👕", 1),
            Good("ate_well", "🥕", 1), Good("kind", "💛", 2), Good("bed", "🛏️", 2),
        )
        AgeGroup.CHILD -> listOf(
            Good("teeth", "🪥", 1), Good("homework", "📚", 3), Good("tidy_room", "🧹", 2),
            Good("helped", "🤝", 2), Good("reading", "📖", 2), Good("bed", "🛏️", 2), Good("kind", "💛", 2),
        )
        AgeGroup.PRETEEN -> listOf(
            Good("homework", "📚", 5), Good("chores", "🧺", 4), Good("reading", "📖", 3),
            Good("sport", "⚽", 3), Good("helped", "🤝", 3), Good("screen_off", "📵", 4), Good("bed", "🛏️", 2),
        )
        AgeGroup.TEEN -> listOf(
            Good("homework", "📚", 8), Good("chores", "🧺", 6), Good("on_time", "⏰", 5),
            Good("sport", "⚽", 5), Good("helped", "🤝", 5), Good("screen_off", "📵", 6), Good("respect", "🗣️", 5),
        )
    }

    /** Things that cost points: kept smaller than the good ones, so a good day always outweighs a slip. */
    fun notGood(g: AgeGroup): List<NotGood> = when (g) {
        AgeGroup.LITTLE -> listOf(
            NotGood("whining", "😩", 1), NotGood("no_tidy", "🧸", 1), NotGood("shouting", "📢", 1),
        )
        AgeGroup.CHILD -> listOf(
            NotGood("shouting", "📢", 1), NotGood("no_tidy", "🧹", 1), NotGood("not_listening", "👂", 1),
            NotGood("rude", "😤", 2), NotGood("teasing", "😜", 2),
        )
        AgeGroup.PRETEEN -> listOf(
            NotGood("no_homework", "📚", 3), NotGood("no_chores", "🧺", 2), NotGood("rude", "😤", 3),
            NotGood("screen_over", "📱", 3), NotGood("teasing", "😜", 2),
        )
        AgeGroup.TEEN -> listOf(
            NotGood("no_homework", "📚", 5), NotGood("no_chores", "🧺", 4), NotGood("rude", "😤", 5),
            NotGood("late", "⏰", 5), NotGood("screen_over", "📱", 4),
        )
    }

    fun challenges(g: AgeGroup): List<Challenge> = when (g) {
        AgeGroup.LITTLE -> listOf(
            Challenge("hitting", "✋", "calm_3"), Challenge("tantrum", "🌪️", "calm_3"), Challenge("not_listening", "👂", "calm_3"),
        )
        AgeGroup.CHILD -> listOf(
            Challenge("hitting", "✋", "screen_minus_15"), Challenge("rude", "😤", "calm_5"),
            Challenge("not_listening", "👂", "screen_minus_10"), Challenge("lying", "🤥", "early_bed_15"),
        )
        AgeGroup.PRETEEN -> listOf(
            Challenge("rude", "😤", "screen_minus_30"), Challenge("homework_missed", "📚", "no_games_evening"),
            Challenge("lying", "🤥", "privilege_day"), Challenge("fighting", "✋", "screen_minus_30"),
        )
        AgeGroup.TEEN -> listOf(
            Challenge("late", "⏰", "curfew_earlier"), Challenge("rude", "😤", "phone_evening"),
            Challenge("homework_missed", "📚", "no_outing"), Challenge("lying", "🤥", "privilege_day"),
        )
    }

    fun prizes(g: AgeGroup): List<Prize> = when (g) {
        AgeGroup.LITTLE -> listOf(
            Prize("sticker", "⭐", 3), Prize("story", "📖", 4), Prize("park", "🛝", 8), Prize("small_toy", "🧸", 15),
        )
        AgeGroup.CHILD -> listOf(
            Prize("screen_30", "📺", 8), Prize("choose_dinner", "🍕", 12), Prize("park", "🛝", 15),
            Prize("stay_up_15", "🌙", 10), Prize("small_toy", "🧸", 30),
        )
        AgeGroup.PRETEEN -> listOf(
            Prize("screen_60", "🎮", 25), Prize("friend_over", "👫", 40), Prize("choose_dinner", "🍕", 20),
            Prize("cinema", "🎬", 80), Prize("money_5", "💶", 60),
        )
        AgeGroup.TEEN -> listOf(
            Prize("curfew_later", "🌙", 60), Prize("screen_60", "🎮", 40), Prize("outing_friends", "👫", 80),
            Prize("money_10", "💶", 120), Prize("choose_weekend", "🗓️", 150),
        )
    }

    /** A new child with the suggestions for their age, in [text]'s words (the app's language). */
    fun addChild(f: Family, name: String, birthYear: Int, birthMonth: Int, avatar: String, colour: Int, today: Day, text: (String) -> String): Child {
        val child = Child(f.newId(), name, birthYear, birthMonth, avatar, colour)
        f.children += child
        val g = child.group(today)
        for (p in good(g)) f.behaviours += Behaviour(f.newId(), child.id, text("good_" + p.key), p.emoji, Kind.GOOD, value = p.value)
        for (p in notGood(g)) f.behaviours += Behaviour(f.newId(), child.id, text("notgood_" + p.key), p.emoji, Kind.NOT_GOOD, value = p.value)
        for (p in challenges(g)) f.behaviours += Behaviour(f.newId(), child.id, text("challenge_" + p.key), p.emoji, Kind.CHALLENGE, consequence = text("consequence_" + p.consequenceKey))
        for (p in prizes(g)) f.rewards += Reward(f.newId(), child.id, text("prize_" + p.key), p.emoji, p.cost)
        // Saving towards the middle reward to begin with.
        child.goalRewardId = f.rewardsOf(child.id).sortedBy { it.cost }.let { it.getOrNull(it.size / 2)?.id }
        return child
    }

    /** All text keys the presets use (the app's translations must have every one). */
    fun allKeys(): Set<String> = AgeGroup.values().flatMap { g ->
        good(g).map { "good_" + it.key } + notGood(g).map { "notgood_" + it.key } + challenges(g).flatMap { listOf("challenge_" + it.key, "consequence_" + it.consequenceKey) } +
            prizes(g).map { "prize_" + it.key }
    }.toSet()
}
