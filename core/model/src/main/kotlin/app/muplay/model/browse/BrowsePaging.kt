package app.muplay.model.browse

/**
 * The slice of a child list one `onGetChildren` call asked for.
 *
 * Four lines, and every one of them exists because of a real value Android Auto sends. `pageSize`
 * arrives as `Int.MAX_VALUE` from a client that wants the whole list, and `page * pageSize` is then
 * negative for every page after the first -- `subList` with a negative index throws inside a
 * `ListenableFuture`, where the exception never reaches a log a driver could see and the symptom is
 * an empty screen.
 *
 * In `:core:model` rather than beside the callback so that the arithmetic is Tier 1: the overflow
 * above is a pure-`Int` fact and needs no emulator to be held to account.
 */
object BrowsePaging {

  /**
   * [items]`[page * pageSize until (page + 1) * pageSize]`, clamped at both ends.
   *
   * A nonsensical request -- a negative page, a non-positive size -- is an **empty list**, not an
   * exception: every one of these arrives from software this project does not own, and the honest
   * answer to "give me page -1" is "there is nothing there". An exception would be swallowed by
   * Media3's future and reach a car as the same empty screen anyway, minus the ability to reason
   * about it.
   */
  fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    // Long arithmetic, deliberately: this is the overflow, and it is not hypothetical. At
    // `pageSize = Int.MAX_VALUE` and `page = 1`, the Int product is -2147483648.
    val from = page.toLong() * pageSize.toLong()
    if (from >= items.size) return emptyList()
    val to = minOf(from + pageSize.toLong(), items.size.toLong())
    return items.subList(from.toInt(), to.toInt())
  }
}
