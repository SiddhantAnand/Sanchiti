export type ThemeId = 'midnight' | 'sage' | 'mintpaper' | 'seafoam';

export const THEMES: { id: ThemeId; name: string; desc: string; swatch: [string, string, string] }[] = [
  { id: 'sage', name: 'Sage Ledger', desc: 'Sage paper, deep pine, brass', swatch: ['#f3f6f2', '#2f6b55', '#a8834a'] },
  { id: 'mintpaper', name: 'Mint Paper', desc: 'Mint white, emerald teal, navy', swatch: ['#f1f8f5', '#0f766e', '#1e3a5f'] },
  { id: 'seafoam', name: 'Seafoam Slate', desc: 'Cool seafoam, ocean teal, jade', swatch: ['#eef5f6', '#0e6e85', '#159a74'] },
  { id: 'midnight', name: 'Midnight', desc: 'Dark navy with violet (original)', swatch: ['#0a0e1a', '#8b7bff', '#2dd4bf'] },
];

const KEY = 'sanchiti.theme';

export function savedTheme(): ThemeId {
  const t = localStorage.getItem(KEY);
  return THEMES.some((x) => x.id === t) ? (t as ThemeId) : 'seafoam';
}

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
  const bg = THEMES.find((x) => x.id === id)?.swatch[0];
  if (bg) document.querySelector('meta[name="theme-color"]')?.setAttribute('content', bg);
  localStorage.setItem(KEY, id);
}
