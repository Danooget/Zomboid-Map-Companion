-- Passive compatibility diagnostics + recovery when another mod removes our
-- UIElement from UIManager. No direct GL calls, no Viewpoint patching.
local ticks = 0
local missingLogged = false
local failureLogged = false

Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks < 120 then return end
    ticks = 0
    if not liveWebMapUiHealthTick then
        if not missingLogged then
            missingLogged = true
            print("[LiveWebMap-Viewpoint] Java UI health bridge unavailable")
        end
        return
    end
    local ok, err = pcall(liveWebMapUiHealthTick)
    if not ok and not failureLogged then
        failureLogged = true
        print("[LiveWebMap-Viewpoint] UI health bridge failed: " .. tostring(err))
    end
end)
