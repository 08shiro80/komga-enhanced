export interface ThemeColors {
  base: string
  primary: string
  secondary: string
  accent: string
  'contrast-1': string
  'contrast-light-2': string
  diff: string
}

export interface ThemePreset {
  name: string
  label: string
  icon: string
  light: ThemeColors
  dark: ThemeColors
}

export const THEME_PRESETS: ThemePreset[] = [
  {
    name: 'default',
    label: 'Default',
    icon: 'mdi-palette',
    light: {
      base: '#FFFFFF',
      primary: '#005ed3',
      secondary: '#fec000',
      accent: '#ff0335',
      'contrast-1': '#F5F5F5',
      'contrast-light-2': '#616161',
      diff: '#C8E6C9',
    },
    dark: {
      base: '#000000',
      primary: '#78baec',
      secondary: '#fec000',
      accent: '#ff0335',
      'contrast-1': '#212121',
      'contrast-light-2': '#BDBDBD',
      diff: '#1B5E20',
    },
  },
  {
    name: 'amoled',
    label: 'AMOLED',
    icon: 'mdi-cellphone',
    light: {
      base: '#FFFFFF',
      primary: '#005ed3',
      secondary: '#fec000',
      accent: '#ff0335',
      'contrast-1': '#F5F5F5',
      'contrast-light-2': '#616161',
      diff: '#C8E6C9',
    },
    dark: {
      base: '#000000',
      primary: '#BB86FC',
      secondary: '#03DAC6',
      accent: '#CF6679',
      'contrast-1': '#0a0a0a',
      'contrast-light-2': '#BDBDBD',
      diff: '#1B5E20',
    },
  },
  {
    name: 'nord',
    label: 'Nord',
    icon: 'mdi-snowflake',
    light: {
      base: '#ECEFF4',
      primary: '#5E81AC',
      secondary: '#EBCB8B',
      accent: '#BF616A',
      'contrast-1': '#E5E9F0',
      'contrast-light-2': '#4C566A',
      diff: '#A3BE8C',
    },
    dark: {
      base: '#2E3440',
      primary: '#88C0D0',
      secondary: '#EBCB8B',
      accent: '#BF616A',
      'contrast-1': '#3B4252',
      'contrast-light-2': '#D8DEE9',
      diff: '#A3BE8C',
    },
  },
  {
    name: 'dracula',
    label: 'Dracula',
    icon: 'mdi-bat',
    light: {
      base: '#F8F8F2',
      primary: '#6272A4',
      secondary: '#F1FA8C',
      accent: '#FF79C6',
      'contrast-1': '#EEEEE8',
      'contrast-light-2': '#44475A',
      diff: '#50FA7B',
    },
    dark: {
      base: '#282A36',
      primary: '#BD93F9',
      secondary: '#F1FA8C',
      accent: '#FF79C6',
      'contrast-1': '#44475A',
      'contrast-light-2': '#F8F8F2',
      diff: '#50FA7B',
    },
  },
  {
    name: 'solarized',
    label: 'Solarized',
    icon: 'mdi-white-balance-sunny',
    light: {
      base: '#FDF6E3',
      primary: '#268BD2',
      secondary: '#B58900',
      accent: '#DC322F',
      'contrast-1': '#EEE8D5',
      'contrast-light-2': '#586E75',
      diff: '#859900',
    },
    dark: {
      base: '#002B36',
      primary: '#268BD2',
      secondary: '#B58900',
      accent: '#DC322F',
      'contrast-1': '#073642',
      'contrast-light-2': '#93A1A1',
      diff: '#859900',
    },
  },
  {
    name: 'green',
    label: 'Green',
    icon: 'mdi-leaf',
    light: {
      base: '#FFFFFF',
      primary: '#2E7D32',
      secondary: '#FFC107',
      accent: '#FF5722',
      'contrast-1': '#F1F8E9',
      'contrast-light-2': '#616161',
      diff: '#C8E6C9',
    },
    dark: {
      base: '#1A1A1A',
      primary: '#66BB6A',
      secondary: '#FFC107',
      accent: '#FF5722',
      'contrast-1': '#263238',
      'contrast-light-2': '#B0BEC5',
      diff: '#1B5E20',
    },
  },
  {
    name: 'red',
    label: 'Red',
    icon: 'mdi-fire',
    light: {
      base: '#FFFFFF',
      primary: '#C62828',
      secondary: '#FF8F00',
      accent: '#1565C0',
      'contrast-1': '#FFEBEE',
      'contrast-light-2': '#616161',
      diff: '#C8E6C9',
    },
    dark: {
      base: '#1A1A1A',
      primary: '#EF5350',
      secondary: '#FF8F00',
      accent: '#42A5F5',
      'contrast-1': '#2C1010',
      'contrast-light-2': '#BDBDBD',
      diff: '#1B5E20',
    },
  },
]
