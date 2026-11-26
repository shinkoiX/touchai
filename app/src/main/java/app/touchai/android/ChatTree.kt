package app.touchai.android

import java.util.UUID

/** One node in a conversation tree. Sibling nodes sharing [parentId] represent retry branches. */
data class ChatTreeNode(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val turn: ChatTurn,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Tree of conversation turns supporting retry branching at any depth.
 * - [activeLeafId] tracks the leaf currently displayed to the user.
 * - [latestPath] walks the newest branch (most recently created turn) for history search & summary.
 */
data class ChatTree(
    val nodes: List<ChatTreeNode> = emptyList(),
    val activeLeafId: String? = null,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    val hasBranches: Boolean
        get() {
            if (nodes.size <= 1) return false
            val seenParents = HashSet<String?>(nodes.size)
            for (node in nodes) {
                if (!seenParents.add(node.parentId)) return true
            }
            return false
        }

    fun node(id: String?): ChatTreeNode? = id?.let { target -> nodes.firstOrNull { it.id == target } }

    fun siblingsOf(nodeId: String): List<ChatTreeNode> {
        val target = node(nodeId) ?: return emptyList()
        return nodes.filter { it.parentId == target.parentId }
    }

    fun childrenOf(parentId: String?): List<ChatTreeNode> = nodes.filter { it.parentId == parentId }

    fun pathTo(leafId: String?): List<ChatTreeNode> {
        if (nodes.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.id }
        var current = (leafId?.let(byId::get) ?: latestLeaf()) ?: return emptyList()
        val visited = HashSet<String>()
        val reversed = ArrayList<ChatTreeNode>()
        while (visited.add(current.id)) {
            reversed.add(current)
            val parent = current.parentId?.let(byId::get) ?: break
            current = parent
        }
        return reversed.asReversed()
    }

    fun activePath(): List<ChatTreeNode> = pathTo(activeLeafId ?: latestLeaf()?.id)

    fun activeTurns(): List<ChatTurn> = activePath().map { it.turn }

    fun latestLeaf(): ChatTreeNode? {
        if (nodes.isEmpty()) return null
        // Pick the most recently created node (tie-break: later in list), then descend to its newest leaf.
        var newest = nodes.first()
        for (i in 1 until nodes.size) {
            val candidate = nodes[i]
            if (candidate.createdAt >= newest.createdAt) newest = candidate
        }
        return descendToNewestLeaf(newest)
    }

    fun latestPath(): List<ChatTreeNode> = pathTo(latestLeaf()?.id)

    fun latestTurns(): List<ChatTurn> = latestPath().map { it.turn }

    fun appendTurn(turn: ChatTurn, now: Long = System.currentTimeMillis()): ChatTree {
        val parentId = activePath().lastOrNull()?.id
        val timestamp = maxOf(now, (nodes.maxOfOrNull { it.createdAt } ?: 0L) + 1L)
        val created = ChatTreeNode(parentId = parentId, turn = turn, createdAt = timestamp)
        return ChatTree(nodes = nodes + created, activeLeafId = created.id)
    }

    /** Creates a new sibling branch at the same parent as [targetNodeId], preserving the old branch and its subtree. */
    fun branchRetry(targetNodeId: String, turn: ChatTurn, now: Long = System.currentTimeMillis()): ChatTree {
        val target = node(targetNodeId) ?: return appendTurn(turn, now)
        val timestamp = maxOf(now, (nodes.maxOfOrNull { it.createdAt } ?: 0L) + 1L)
        val sibling = ChatTreeNode(parentId = target.parentId, turn = turn, createdAt = timestamp)
        return ChatTree(nodes = nodes + sibling, activeLeafId = sibling.id)
    }

    fun updateNode(nodeId: String, transform: (ChatTurn) -> ChatTurn): ChatTree {
        if (nodes.none { it.id == nodeId }) return this
        return copy(nodes = nodes.map { if (it.id == nodeId) it.copy(turn = transform(it.turn)) else it })
    }

    fun updateActiveLeaf(transform: (ChatTurn) -> ChatTurn): ChatTree {
        val leafId = activePath().lastOrNull()?.id ?: return this
        return updateNode(leafId, transform)
    }

    /** Switches view to [siblingNodeId] and descends to its newest descendant leaf so the full branch renders. */
    fun selectBranch(siblingNodeId: String): ChatTree {
        val target = node(siblingNodeId) ?: return this
        val leaf = descendToNewestLeaf(target)
        return copy(activeLeafId = leaf.id)
    }

    fun mapTurns(transform: (ChatTurn) -> ChatTurn): ChatTree =
        copy(nodes = nodes.map { it.copy(turn = transform(it.turn)) })

    private fun descendToNewestLeaf(start: ChatTreeNode): ChatTreeNode {
        val childrenByParent = nodes.groupBy { it.parentId }
        val visited = HashSet<String>()
        var current = start
        while (visited.add(current.id)) {
            val children = childrenByParent[current.id].orEmpty()
            if (children.isEmpty()) break
            var newest = children.first()
            for (i in 1 until children.size) {
                val candidate = children[i]
                if (candidate.createdAt >= newest.createdAt) newest = candidate
            }
            current = newest
        }
        return current
    }

    companion object {
        fun fromLinear(turns: List<ChatTurn>, baseTime: Long = 1L): ChatTree {
            if (turns.isEmpty()) return ChatTree()
            val built = ArrayList<ChatTreeNode>(turns.size)
            var parentId: String? = null
            turns.forEachIndexed { index, turn ->
                val node = ChatTreeNode(
                    id = UUID.randomUUID().toString(),
                    parentId = parentId,
                    turn = turn,
                    createdAt = baseTime + index,
                )
                built.add(node)
                parentId = node.id
            }
            return ChatTree(built, built.last().id)
        }
    }
}
