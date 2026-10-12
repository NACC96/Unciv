/** Readable presentation of actual calls; no additional model request or inferred game action. */
export function summarizeTool(rawName: string, input: any = {}, result?: any, status = "completed") {
  const name = String(rawName ?? "game tool").replace(/^mcp__game__game_/, "game.");
  input = input && typeof input === "object" ? input : {};
  const args = input?.args ?? {};
  const active = status === "in_progress" || status === "started" || status === "running" || status === "pending";
  const point = (p: any) => p && typeof p.x === "number" && typeof p.y === "number" ? `(${p.x}, ${p.y})` : "its destination";
  const unit = (id: unknown) => `unit ${id ?? ""}`.trim();
  let title: string;
  let summary = "";
  switch (name) {
    case "game.observe":
      title = active ? "Reading game state" : "Read game state";
      if (Array.isArray(result?.ownUnits) && Array.isArray(result?.ownCities)) {
        const units = result.ownUnits.length, cities = result.ownCities.length;
        summary = `${units} ${units === 1 ? "unit" : "units"} · ${cities} ${cities === 1 ? "city" : "cities"}`;
      }
      break;
    case "game.message_image": title = active ? "Reading message image" : "Read message image"; break;
    case "game.inspect": title = `${active ? "Inspecting" : "Inspected"} ${unit(input.unitId)}`; break;
    case "game.tile":
      title = active ? "Inspecting tile" : "Inspected tile";
      if (typeof result?.terrain === "string") summary = result.terrain;
      break;
    case "game.map":
      title = active ? "Reading map" : "Read map";
      if (Array.isArray(result?.tiles)) summary = `${result.tiles.length} tiles`;
      break;
    case "game.legal_actions":
      if (input.unitId !== undefined && !input.cityId &&
          ((Array.isArray(result?.improvements) && result.improvements.length > 0) ||
            result?.workInProgress != null || result?.repair != null || result?.canCancelWork === true)) {
        title = active ? "Checking worker options" : "Checked worker options";
        summary = `${Array.isArray(result?.improvements) ? result.improvements.length : 0} improvements`;
      } else title = `${active ? "Checking" : "Checked"} options for ${input.cityId ? "city" : unit(input.unitId)}`;
      break;
    case "game.rules": title = active ? "Reading game rules" : "Read game rules"; break;
    case "game.notes": title = input.notes != null ? (active ? "Saving strategy notes" : "Saved strategy notes") : (active ? "Reading strategy notes" : "Read strategy notes"); break;
    case "game.end_turn": title = active ? "Ending turn" : "Ended turn"; break;
    case "game.action_result": title = active ? "Checking action result" : "Checked action result"; break;
    case "game.act_batch": {
      const requested = Array.isArray(input.actions) ? input.actions.length : 0;
      const applied = typeof result?.applied === "number" ? result.applied : 0;
      if (active) title = `Applying ${requested} ${requested === 1 ? "action" : "actions"}`;
      else if (result?.status === "partial") {
        title = `Applied ${applied} of ${requested} actions`;
        summary = `Stopped: ${result?.rejected?.message ?? "action rejected"}`;
      } else title = `Applied ${applied} ${applied === 1 ? "action" : "actions"}`;
      break;
    }
    case "game.act": {
      switch (input.type) {
        case "unit.found": title = active ? "Founding a city" : "Founded a city"; break;
        case "unit.move": title = `${active ? "Moving" : "Moved"} ${unit(args.unitId)} to ${point(args.destination)}`; break;
        case "unit.attack": title = `${active ? "Attacking" : "Attacked"} at ${point(args.target)}`; break;
        case "unit.order": title = `${active ? "Setting" : "Set"} ${String(args.order ?? "order").toLowerCase().replaceAll("_", " ")} for ${unit(args.unitId)}`; break;
        case "research.select": title = `${active ? "Choosing" : "Selected"} research: ${args.techName ?? "technology"}`; break;
        case "city.produce": title = `${active ? "Updating" : "Updated"} production${args.name ? `: ${args.name}` : ""}`; break;
        case "civics.adopt": title = `${active ? "Adopting" : "Adopted"} ${args.policyName ?? "policy"}`; break;
        case "unit.build": title = `${active ? "Starting" : "Started"} ${args.improvementName ?? "improvement"}`; break;
        case "unit.promote": title = `${active ? "Promoting" : "Promoted"} ${unit(args.unitId)}: ${args.promotionName ?? "promotion"}`; break;
        case "unit.upgrade": title = `${active ? "Upgrading" : "Upgraded"} ${unit(args.unitId)}`; break;
        case "unit.pillage": title = `${active ? "Pillaging" : "Pillaged"} with ${unit(args.unitId)}`; break;
        case "unit.repair": title = `${active ? "Assigning" : "Assigned"} repairs to ${unit(args.unitId)}`; break;
        case "unit.great": title = `${active ? "Using" : "Used"} great person action: ${String(args.action ?? "").toLowerCase().replaceAll("_", " ")}`; break;
        case "city.citizens": title = active ? "Managing city citizens" : "Updated city citizens"; break;
        case "diplomacy.declareWar": title = `${active ? "Declaring" : "Declared"} war on ${args.targetCivId ?? "civilization"}`; break;
        case "diplomacy.makePeace": title = `${active ? "Making" : "Made"} peace with ${args.targetCivId ?? "civilization"}`; break;
        case "diplomacy.friendship": title = `${active ? "Proposing" : "Proposed"} friendship to ${args.targetCivId ?? "civilization"}`; break;
        case "diplomacy.proposeTrade": title = `${active ? "Proposing" : "Proposed"} trade with ${args.targetCivId ?? "civilization"}`; break;
        case "diplomacy.message":
          title = `${active ? "Sending message" : result?.status === "applied" ? "Sent message" : "Message call"} to ${args.targetCivId ?? "civilization"}`;
          if (!active) summary = result?.status === "applied" ? "Queued for their next turn." : "Check action_result to confirm the send.";
          break;
        case "diplomacy.acknowledgeMessage":
          title = active ? "Acknowledging message" : result?.status === "applied" ? "Message acknowledged" : "Acknowledgement call";
          if (!active) summary = result?.status === "applied" ? (result?.result?.acknowledgementText ?? "Receipt acknowledged; no agreement implied.") : "Check action_result to confirm acknowledgement.";
          break;
        case "diplomacy.respondTrade": title = `${args.accept ? (active ? "Accepting" : "Accepted") : (active ? "Declining" : "Declined")} trade`; break;
        case "citystate.giftGold": title = `${active ? "Giving" : "Gave"} ${args.amount ?? ""} gold to ${args.cityStateId ?? "city-state"}`; break;
        case "city.buyTile": title = `${active ? "Buying" : "Bought"} tile ${point(args.tilePos)}`; break;
        case "conquest.resolve": title = `${active ? "Choosing" : "Chose"} conquered city disposition: ${String(args.disposition ?? "").toLowerCase()}`; break;
        default: title = `${active ? "Running" : "Completed"} ${input.type ?? "game action"}`;
      }
      break;
    }
    default: title = `${active ? "Calling" : "Called"} ${name}`;
  }
  // A completed transport call can contain an engine rejection. Never label that as success.
  if (result?.status === "rejected" || result?.status === "failed" || status === "failed" || status === "error") {
    title = result?.status === "rejected" ? "Action rejected" : "Tool call failed";
    summary = result?.message ?? result?.error?.message ?? result?.code ?? "Call failed";
    status = "failed";
  }
  return { title, summary, status: active && status !== "failed" ? "running" : status === "failed" ? "failed" : "completed" };
}

export function toolResult(value: any): any {
  if (Array.isArray(value)) {
    for (const part of value) if (typeof part.text === "string") {
      try { return JSON.parse(part.text); } catch { /* Preserve non-JSON results in the full transcript. */ }
    }
    return undefined;
  }
  if (typeof value === "string") { try { return JSON.parse(value); } catch { return undefined; } }
  return value;
}
