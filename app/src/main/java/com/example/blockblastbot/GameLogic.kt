package com.example.blockblastbot

/**
 * Représente le plateau 8x8 sous forme d'un seul Long (64 bits).
 * Le bit (row*8 + col) vaut 1 si la case est occupée.
 * Utiliser un bitboard rend toutes les opérations (placement, test de
 * collision, détection de lignes complètes) extrêmement rapides,
 * ce qui permet d'explorer TOUTES les combinaisons possibles pour
 * les 3 pièces en quelques millisecondes.
 */
object Board {
    const val SIZE = 8

    val ROW_MASKS = LongArray(SIZE) { r -> (0xFFL) shl (r * SIZE) }
    val COL_MASKS = LongArray(SIZE) { c ->
        var m = 0L
        for (r in 0 until SIZE) m = m or (1L shl (r * SIZE + c))
        m
    }

    fun bit(row: Int, col: Int): Long = 1L shl (row * SIZE + col)

    fun isEmpty(board: Long, row: Int, col: Int): Boolean =
        (board and bit(row, col)) == 0L
}

/**
 * Une pièce détectée = liste de cases occupées, normalisées pour que
 * la case la plus en haut à gauche soit (0,0).
 */
data class Piece(val cells: List<Pair<Int, Int>>) {
    val height: Int = (cells.maxOfOrNull { it.first } ?: 0) + 1
    val width: Int = (cells.maxOfOrNull { it.second } ?: 0) + 1

    companion object {
        fun normalize(rawCells: List<Pair<Int, Int>>): Piece {
            if (rawCells.isEmpty()) return Piece(emptyList())
            val minR = rawCells.minOf { it.first }
            val minC = rawCells.minOf { it.second }
            return Piece(rawCells.map { (r, c) -> Pair(r - minR, c - minC) })
        }
    }
}

data class Placement(val pieceIndex: Int, val piece: Piece, val row: Int, val col: Int)

data class SolveResult(
    val order: List<Placement>, // ordre et positions choisis pour les 3 pièces
    val finalBoard: Long,
    val totalScore: Int
)

/**
 * Le solveur : essaie TOUTES les combinaisons (ordre des 3 pièces x
 * toutes les positions valides pour chacune) et garde celle qui
 * maximise le score, avec un bonus heuristique qui favorise les
 * plateaux qui restent "ouverts" (peu de trous) pour la suite de la partie.
 */
object Solver {

    // Barème de score approximatif (Block Blast donne des points par
    // case posée + un gros bonus par ligne/colonne complétée, avec un
    // effet "combo" quand plusieurs lignes tombent en même temps).
    // Ajuste ces constantes si tu observes un barème différent en jouant.
    private const val POINTS_PER_CELL = 1
    private const val POINTS_PER_LINE = 10
    private const val COMBO_MULTIPLIER = 5 // bonus supplémentaire = lignes^2 * COMBO_MULTIPLIER

    private fun candidatePositions(board: Long, piece: Piece): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        if (piece.cells.isEmpty()) return result
        for (row in 0..(Board.SIZE - piece.height)) {
            for (col in 0..(Board.SIZE - piece.width)) {
                var mask = 0L
                var fits = true
                for ((dr, dc) in piece.cells) {
                    val bit = Board.bit(row + dr, col + dc)
                    if (board and bit != 0L) { fits = false; break }
                    mask = mask or bit
                }
                if (fits) result.add(Pair(row, col))
            }
        }
        return result
    }

    private fun placementMask(piece: Piece, row: Int, col: Int): Long {
        var mask = 0L
        for ((dr, dc) in piece.cells) mask = mask or Board.bit(row + dr, col + dc)
        return mask
    }

    /** Retire les lignes/colonnes complètes. Retourne (nouveauPlateau, nbLignesEffacees) */
    private fun clearLines(board: Long): Pair<Long, Int> {
        var newBoard = board
        var cleared = 0
        val fullRows = mutableListOf<Int>()
        val fullCols = mutableListOf<Int>()
        for (r in 0 until Board.SIZE) {
            if ((board and Board.ROW_MASKS[r]) == Board.ROW_MASKS[r]) fullRows.add(r)
        }
        for (c in 0 until Board.SIZE) {
            if ((board and Board.COL_MASKS[c]) == Board.COL_MASKS[c]) fullCols.add(c)
        }
        for (r in fullRows) { newBoard = newBoard and Board.ROW_MASKS[r].inv(); cleared++ }
        for (c in fullCols) { newBoard = newBoard and Board.COL_MASKS[c].inv(); cleared++ }
        return Pair(newBoard, cleared)
    }

    private fun heuristic(board: Long): Double {
        // Favorise les plateaux avec beaucoup de cases vides et peu de
        // "trous" isolés (case vide entourée de cases pleines, difficile
        // à combler plus tard).
        var empty = 0
        var holes = 0
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (Board.isEmpty(board, r, c)) {
                    empty++
                    val neighborsFilled = listOf(
                        r - 1 to c, r + 1 to c, r to c - 1, r to c + 1
                    ).count { (nr, nc) ->
                        nr !in 0 until Board.SIZE || nc !in 0 until Board.SIZE ||
                            !Board.isEmpty(board, nr, nc)
                    }
                    if (neighborsFilled >= 3) holes++
                }
            }
        }
        return empty * 1.0 - holes * 4.0
    }

    /**
     * Cherche la meilleure façon de placer les 3 pièces (dans n'importe
     * quel ordre). Retourne null si aucune combinaison ne permet de
     * placer les 3 (plateau bloqué -> à gérer par l'appelant, ex: fin de partie).
     */
    fun solve(startBoard: Long, pieces: List<Piece>): SolveResult? {
        var best: SolveResult? = null
        val indices = pieces.indices.toList()

        fun permutations(list: List<Int>): List<List<Int>> {
            if (list.size <= 1) return listOf(list)
            val result = mutableListOf<List<Int>>()
            for (i in list.indices) {
                val rest = list.toMutableList().also { it.removeAt(i) }
                for (p in permutations(rest)) result.add(listOf(list[i]) + p)
            }
            return result
        }

        for (order in permutations(indices)) {
            searchOrder(startBoard, order, pieces, 0, 0, emptyList()) { finalBoard, score, placements ->
                val h = heuristic(finalBoard)
                val candidateValue = score + h
                val bestValue = (best?.let { it.totalScore + heuristic(it.finalBoard) }) ?: Double.NEGATIVE_INFINITY
                if (candidateValue > bestValue) {
                    best = SolveResult(placements, finalBoard, score)
                }
            }
        }
        return best
    }

    private fun searchOrder(
        board: Long,
        order: List<Int>,
        pieces: List<Piece>,
        depth: Int,
        scoreSoFar: Int,
        placementsSoFar: List<Placement>,
        onLeaf: (Long, Int, List<Placement>) -> Unit
    ) {
        if (depth == order.size) {
            onLeaf(board, scoreSoFar, placementsSoFar)
            return
        }
        val pieceIndex = order[depth]
        val piece = pieces[pieceIndex]
        val positions = candidatePositions(board, piece)
        if (positions.isEmpty()) {
            // Cette pièce ne rentre pas -> cette branche/ordre est invalide, on l'abandonne.
            return
        }
        for ((row, col) in positions) {
            val mask = placementMask(piece, row, col)
            val placedBoard = board or mask
            val cellsPlaced = piece.cells.size
            val (clearedBoard, linesCleared) = clearLines(placedBoard)
            val moveScore = cellsPlaced * POINTS_PER_CELL +
                linesCleared * POINTS_PER_LINE +
                (linesCleared * linesCleared) * COMBO_MULTIPLIER
            searchOrder(
                clearedBoard,
                order,
                pieces,
                depth + 1,
                scoreSoFar + moveScore,
                placementsSoFar + Placement(pieceIndex, piece, row, col),
                onLeaf
            )
        }
    }
}
