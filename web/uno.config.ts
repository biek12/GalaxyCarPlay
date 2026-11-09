import { defineConfig, presetWind3, presetIcons } from 'unocss'

export default defineConfig({
  presets: [presetWind3(), presetIcons()],
  shortcuts: {
    'ar-card': 'rounded-14px bg-white dark:bg-[#14171d] shadow-sm',
    'ar-chip': 'inline-flex items-center gap-4px rounded-full px-10px py-3px text-12px',
  },
  theme: {
    colors: {
      accent: '#00b96b',
    },
  },
})
