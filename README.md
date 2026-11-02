# ChessCraft (Fabric 1.21.11)

A complete chess system for Minecraft: a table base, a 3x3 chess board that shows the game live in the
world, twelve placeable chess pieces, full FIDE rules, a built-in chess engine from 100 to 3200 Elo,
and a chess-site style screen with post-game review.

## Blocks (14)

| Block | Notes |
|---|---|
| Chess Table Base | Wooden pedestal. The board can only be placed on top of it. |
| Chess Board | 3x3 blocks wide, 1 high. Stores the pieces and the game; shows every move live in the world. |
| 12 pieces | White/Black King, Queen, Rook, Bishop, Knight, Pawn. Place them anywhere as decoration (16 directions). |

## How to play

1. Place a **Chess Table Base**, then the **Chess Board** on top of it (needs a free 3x3 area).
   The side you place it from is White's side.
2. **Right-click the board holding pieces** to put them in. They stand on their starting squares
   automatically; the board holds one full army (8 pawns, 2 rooks, 2 knights, 2 bishops, queen, king per color).
3. **Right-click with an empty hand** to open the chess screen:
   - **Mode**: *Standard* (the stored pieces) or *Custom* (drag any position with unlimited virtual pieces;
     White always moves first).
   - **Play Bot** (level 100-3200, your color, take-backs, hints) or **Play a Friend** (online or same screen).
   - **Time**: 1, 3, 5, 10, 30 minutes, 1 hour, or with increment (1|1, 2|1, 3|2 ... 60|30).
   - **Settings**: premoves, legal-move dots, last-move highlight, take all pieces out.
4. Play by clicking or dragging. Right-drag draws arrows, right-click marks a square red.
   Browse earlier moves with the arrows under the move list; against the bot you can continue from any earlier move.
5. After the game: **Game Review** with accuracy, an evaluation bar and graph, move classifications
   (brilliant, great, best, excellent, good, book, inaccuracy, mistake, miss, blunder) and best-move arrows.

All rules are covered: castling, en passant, promotions, checkmate, stalemate, threefold repetition,
fifty-move rule, insufficient material, timeouts, resignation and draw offers.

## Crafting

- Table Base: 3 wooden slabs, 1 log, 3 planks.
- Board: dark oak and birch planks in a checker pattern around a gold ingot.
- Pieces: stonecutter - birch planks for white, dark oak planks for black (4 pawns per plank).

## Engine

Pure Java, server side: bitboards with magic move generation, iterative deepening PVS, transposition table,
null-move pruning, LMR, futility pruning, SEE, quiescence search, a tapered hand-crafted evaluation and an
opening book. Below 2000 Elo the bot plays with deliberate mistakes; from 2000 up it plays its best move with a
growing time budget. Elo labels are approximate. Reviews run on the player's own computer.

## Development

```
gradlew build              # compile + unit tests (perft, rules, engine, review)
gradlew runClientGameTest  # end-to-end test in a real client, screenshots in build/run/clientGameTest/screenshots
gradlew runClient          # play in a dev client
python tools/gen_assets.py # regenerate textures, models, blockstates, loot tables, GUI sprites and icons
```

The built mod is `build/libs/chesscraft-1.0.0.jar` (requires Fabric Loader and Fabric API).

## License

CC0 1.0 (public domain) - see [LICENSE](LICENSE). The bundled Open Sans font is Apache 2.0.
