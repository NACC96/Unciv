/** Manual diagnostic: start the game MCP server and print url+bearer, run until killed. */
import { FakeGameService } from "../dist/fake-game-mcp.js";
import { startGameMcpServer } from "../dist/game-mcp-server.js";

const server = await startGameMcpServer(new FakeGameService(), {
  matchId: "diag", timelineId: "t", civId: "c", runId: "r", authorityEpoch: 1,
});
console.log(JSON.stringify({ url: server.url, bearer: server.bearer }));
setInterval(() => {}, 1 << 30);
