
print("[LiveWebMap] Lua mod loaded successfully")

Events.OnKeyPressed.Add(function(key)
    if key == Keyboard.KEY_F7 then
        -- Only toggle the map when a player exists in-game.
        if getPlayer() ~= nil then
            if liveWebMapTogglePanel then
                liveWebMapTogglePanel()
            else
                print("[LiveWebMap] Panel toggle function not found")
            end
        end
    end
end)
