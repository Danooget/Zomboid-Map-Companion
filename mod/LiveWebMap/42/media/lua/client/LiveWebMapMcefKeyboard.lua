-- LiveWebMap: text-field-only browser keyboard capture (Build 42).
-- Replaces Phase 8 / Keyboard Hotfix. No other Lua or Java files changed.
--
-- Two input paths must be handled:
--   1. UIManager key press/repeat/release events (game shortcuts).
--   2. Character input's continuous key polling (WASD movement/context).
-- Do not disable global GameKeyboard Lua events: F7 and other UI need them.

require "ISUI/ISPanel"

LiveWebMapKeySink = ISPanel:derive("LiveWebMapKeySink")

local keyboardPanel = nil
local installFailed = false
local activeLastTick = false
local firstKeyLogged = false

-- State of the player's input flags before LiveWebMap started capturing.
local guardedPlayer = nil
local savedMoveBlocked = nil
local savedContextIgnored = nil
local movementGuardAvailable = false
local contextGuardAvailable = false
local movementWarned = false
local contextWarned = false

local function logOnce(kind, msg)
    if kind == "movement" then
        if movementWarned then return end
        movementWarned = true
    else
        if contextWarned then return end
        contextWarned = true
    end
    print("[LiveWebMap-MCEF] Input guard: " .. tostring(msg))
end

local function isTyping()
    -- Avoid calling the Java method until ZombieBuddy registers its Lua bridge.
    if not liveWebMapMcefKeyboardActive then return false end
    local ok, active = pcall(liveWebMapMcefKeyboardActive)
    return ok and active == true
end

local function captureKey(key)
    -- F7 always remains available to close the map, even when typing.
    return key ~= Keyboard.KEY_F7 and isTyping()
end

-- Release only flags that were *false* before capture began. If another mod
-- already blocked a control, preserve its original true value.
local function releaseGameGuards()
    if not guardedPlayer then return end
    local player = guardedPlayer

    if movementGuardAvailable and savedMoveBlocked == false then
        local ok, err = pcall(function() player:setBlockMovement(false) end)
        if not ok then logOnce("movement", "movement restore failed: " .. tostring(err)) end
    end
    if contextGuardAvailable and savedContextIgnored == false then
        local ok, err = pcall(function() player:setIgnoreContextKey(false) end)
        if not ok then logOnce("context", "context-key restore failed: " .. tostring(err)) end
    end

    guardedPlayer = nil
    savedMoveBlocked = nil
    savedContextIgnored = nil
    movementGuardAvailable = false
    contextGuardAvailable = false
    print("[LiveWebMap-MCEF] Gameplay input guards released")
end

local function setGameGuards(active)
    if not active then
        releaseGameGuards()
        return
    end

    local player = getSpecificPlayer(0)
    if not player then
        releaseGameGuards()
        return
    end

    if guardedPlayer and guardedPlayer ~= player then
        releaseGameGuards()
    end

    if not guardedPlayer then
        guardedPlayer = player
        local okMove, wasBlocked = pcall(function() return player:isBlockMovement() end)
        if okMove and type(wasBlocked) == "boolean" then
            savedMoveBlocked = wasBlocked
            movementGuardAvailable = true
        else
            logOnce("movement", "isBlockMovement() unavailable: " .. tostring(wasBlocked))
        end
        local okContext, wasIgnored = pcall(function() return player:isIgnoreContextKey() end)
        if okContext and type(wasIgnored) == "boolean" then
            savedContextIgnored = wasIgnored
            contextGuardAvailable = true
        else
            logOnce("context", "isIgnoreContextKey() unavailable: " .. tostring(wasIgnored))
        end
        print("[LiveWebMap-MCEF] Gameplay input guards engaged")
    end

    -- Reassert while typing, because other game systems may clear their flags.
    -- The guard methods run only if they were successfully detected above.
    if movementGuardAvailable then
        local ok, err = pcall(function()
            if not player:isBlockMovement() then player:setBlockMovement(true) end
        end)
        if not ok then
            movementGuardAvailable = false
            logOnce("movement", "setBlockMovement() failed: " .. tostring(err))
        end
    end
    if contextGuardAvailable then
        local ok, err = pcall(function()
            if not player:isIgnoreContextKey() then player:setIgnoreContextKey(true) end
        end)
        if not ok then
            contextGuardAvailable = false
            logOnce("context", "setIgnoreContextKey() failed: " .. tostring(err))
        end
    end
end

function LiveWebMapKeySink:new()
    -- A 1x1 offscreen panel participates in keyboard dispatch, not mouse UI.
    local o = ISPanel.new(self, -100, -100, 1, 1)
    o.background = false
    o.border = false
    return o
end

function LiveWebMapKeySink:onKeyPress(key)
    if not captureKey(key) then return end

    -- Apply movement/context guards as soon as the first key arrives, even if
    -- this frame's OnTick has not executed yet.
    setGameGuards(true)

    if not firstKeyLogged then
        firstKeyLogged = true
        print("[LiveWebMap-MCEF] Full keyboard capture received first key: " .. tostring(key))
    end

    if liveWebMapMcefForwardKey then liveWebMapMcefForwardKey(key, 1) end
    -- Block the vanilla release-triggered shortcut path as well.
    GameKeyboard.eatKeyPress(key)
end

function LiveWebMapKeySink:onKeyRepeat(key)
    if captureKey(key) then
        GameKeyboard.eatKeyPress(key)
    end
end

function LiveWebMapKeySink:onKeyRelease(key)
    if captureKey(key) and liveWebMapMcefForwardKey then
        liveWebMapMcefForwardKey(key, 0)
    end
end

function LiveWebMapKeySink:isKeyConsumed(key)
    -- Critical: prevents OnKeyStartPressed/OnKeyPressed and lower UI handlers.
    return captureKey(key)
end

local function installKeyboardSink()
    if keyboardPanel or installFailed or not liveWebMapMcefKeyboardActive then return end

    local ok, err = pcall(function()
        local p = LiveWebMapKeySink:new()
        p:initialise()
        p:instantiate()
        p:setVisible(true)
        p:setWantKeyEvents(true)
        p:addToUIManager()
        keyboardPanel = p
    end)

    if ok then
        print("[LiveWebMap-MCEF] Full keyboard capture panel installed")
    else
        installFailed = true -- Never trigger thousands of errors from OnTick.
        print("[LiveWebMap-MCEF] Full keyboard capture installation FAILED: " .. tostring(err))
    end
end

Events.OnGameStart.Add(installKeyboardSink)
Events.OnTick.Add(function()
    if not keyboardPanel then installKeyboardSink() end
    local active = keyboardPanel ~= nil and isTyping()

    setGameGuards(active)

    if active ~= activeLastTick then
        activeLastTick = active
        if active then
            keyboardPanel:bringToTop()
        end
        print("[LiveWebMap-MCEF] Full keyboard capture " .. (active and "ON" or "OFF"))
    end
end)
