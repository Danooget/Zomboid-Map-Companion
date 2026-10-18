-- Isolated MCEF smoke test. Leave the existing LiveWebMap UI Lua files alone.
local probeStarted = false

Events.OnGameStart.Add(function()
    if probeStarted then return end
    if not liveWebMapMcefStart then
        print('[LiveWebMap-MCEF] Lua function liveWebMapMcefStart was not registered')
        return
    end
    probeStarted = true
    print('[LiveWebMap-MCEF] Starting in-game Chromium probe')
    liveWebMapMcefStart()
end)

Events.OnTick.Add(function()
    if probeStarted and liveWebMapMcefTick then
        liveWebMapMcefTick()
    end
end)
