-- LiveWebMap direct GPS tracker for Project Zomboid Build 42.
-- No separate GPS mod, external executable, or live.txt file is required.
-- Sends ONLY the locally controlled character's position and facing to Java.

local tickCount = 0
local reportedFirst = false
local reportedNoBridge = false
local errorsLogged = 0

local function survivorName(player)
    -- Prefer the survivor's in-game first/last name, not the multiplayer login.
    local descriptor = player:getDescriptor()
    if descriptor then
        local first = descriptor:getForename() or ''
        local last = descriptor:getSurname() or ''
        local full = (tostring(first) .. ' ' .. tostring(last)):gsub('^%s+', ''):gsub('%s+$', '')
        if full ~= '' then return full end
    end
    local display = player:getDisplayName()
    if display and display ~= '' then return tostring(display) end
    return 'Survivor'
end

local function sendPosition()
    tickCount = tickCount + 1
    if tickCount % 30 ~= 0 then return end

    if not liveWebMapMcefGpsSample then
        if not reportedNoBridge then
            print('[LiveWebMap-GPS] Waiting for Java GPS entry point')
            reportedNoBridge = true
        end
        return
    end

    local player = getPlayer()
    if not player or player:isDead() then return end

    local success, err = pcall(function()
        -- The original GPS Companion file uses world x/y/z and direction fx/fy.
        local fx, fy = player:getLookDirectionX(), player:getLookDirectionY()
        local name = survivorName(player)
        liveWebMapMcefGpsSample(name, player:getX(), player:getY(), player:getZ(), fx, fy)
        if not reportedFirst then
            reportedFirst = true
            print('[LiveWebMap-GPS] Reading local player position directly: ' .. name)
        end
    end)
    if not success and errorsLogged < 3 then
        errorsLogged = errorsLogged + 1
        print('[LiveWebMap-GPS] Could not sample local player: ' .. tostring(err))
    end
end

Events.OnTick.Add(sendPosition)
