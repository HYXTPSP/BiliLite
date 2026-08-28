-- ============================================================
-- 自动跳过片头 示例功能插件
-- 依赖 API: events.* / player.* / system.*
-- 逻辑:监听 "videoChanged" 与 "play" 事件,在视频开始播放时向前 seek 跳过片头。
-- 片头秒数可从 system prefs 读取(默认 90 秒)。
-- ============================================================

local skipSeconds = tonumber(system.getPrefs("skip_seconds")) or 90

-- 标记当前视频是否已执行过跳片头(每个视频只跳一次)
local skippedBvid = nil

-- 读取当前视频并向前 seek
local function doSkip()
    local v = player.getCurrentVideo()
    if v == nil or v.bvid == nil then return end
    if skippedBvid == v.bvid then return end
    player.seekTo(skipSeconds)
    skippedBvid = v.bvid
    ui.toast("已跳过片头 " .. skipSeconds .. " 秒")
end

-- 监听播放事件:每次开始播放都尝试跳片头(videoChanged 时重置标记)
events.on("videoChanged", function(ev, data)
    skippedBvid = nil
    doSkip()
end)

events.on("play", function(ev, data)
    doSkip()
end)

system.log("自动跳过片头插件已加载,跳过秒数=" .. skipSeconds)