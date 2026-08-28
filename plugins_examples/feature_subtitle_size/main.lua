-- 字幕大小调节插件
local sizes = { "小", "中", "大" }
local values = { 0, 1, 2 }  -- 对应 subSize

-- 获取当前大小
local current = tonumber(system.getPrefs("sub_size")) or 1  -- 默认中

-- 显示对话框
local function showDialog()
    local buttons = {}
    for i, label in ipairs(sizes) do
        local sizeVal = values[i]
        buttons[label] = function()
            system.setPrefs("sub_size", tostring(sizeVal))
            ui.toast("字幕大小已切换为" .. label .. "，重新进入播放器生效")
        end
    end
    ui.dialog("字幕大小", "选择字幕字体大小（当前：" .. sizes[current+1] .. "）", buttons)
end

-- 加载时弹出一次（用 prefs 记录是否已弹出）
local shown = system.getPrefs("dialog_shown")
if shown ~= "true" then
    system.setPrefs("dialog_shown", "true")
    showDialog()
else
    -- 提示用户可重新触发
    ui.toast("字幕大小已设置，如需修改请重新启用本插件")
end