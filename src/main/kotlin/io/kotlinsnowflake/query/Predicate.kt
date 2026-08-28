package io.kotlinsnowflake.query

/**
 * A node in a WHERE-clause predicate tree.
 *
 * The tree exists so the combinator a group was built with survives into the generated
 * SQL. Rendering fragments to a flat string as they are declared loses that: an `OR`
 * group is indistinguishable from an `AND` group by the time it is appended.
 */
internal sealed interface Predicate {

    /** Renders this node to SQL with `?` placeholders. */
    fun render(): String

    /** The bind values for this node's placeholders, in placeholder order. */
    val params: List<Any?>
}

/** How the children of a [Group] are joined. */
internal enum class Combinator(val keyword: String) { AND("AND"), OR("OR") }

/** A single condition, e.g. `STATUS = ?` or `ID IN (?, ?)`. */
internal class Comparison(
    private val sql: String,
    override val params: List<Any?> = emptyList(),
) : Predicate {
    override fun render(): String = sql
}

/** Two or more predicates joined by a single [Combinator]. */
internal class Group(
    private val combinator: Combinator,
    private val children: List<Predicate>,
) : Predicate {

    override val params: List<Any?> get() = children.flatMap { it.params }

    // A nested group is parenthesized so its combinator binds tighter than the parent's;
    // the outermost group needs no parentheses because nothing surrounds it.
    override fun render(): String =
        children.joinToString(" ${combinator.keyword} ") { child ->
            if (child is Group) "(${child.render()})" else child.render()
        }
}
