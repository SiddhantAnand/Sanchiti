import {
  ArrowLeftRight, Car, CircleQuestionMark, Clapperboard, CreditCard, Ellipsis, Fuel, Gift, GraduationCap, HeartPulse,
  House, Landmark, PiggyBank, Plane, Receipt, Repeat, Scissors, ShoppingBag, ShoppingBasket, Utensils, Zap,
  type LucideIcon,
} from 'lucide-react';

export const UNCATEGORIZED = 'Uncategorized';

type Meta = { color: string; icon: LucideIcon };

// Tuned to sit on the dark navy surfaces at similar perceived brightness, so no single slice shouts.
const META: Record<string, Meta> = {
  'Food & Dining': { color: '#FF8A5B', icon: Utensils },
  Groceries: { color: '#7BD389', icon: ShoppingBasket },
  Transport: { color: '#4FB3FF', icon: Car },
  Fuel: { color: '#FFB547', icon: Fuel },
  Shopping: { color: '#C77DFF', icon: ShoppingBag },
  'Bills & Utilities': { color: '#5EEAD4', icon: Zap },
  'Rent & Housing': { color: '#8B9CFF', icon: House },
  'Health & Medical': { color: '#FF6B8B', icon: HeartPulse },
  Entertainment: { color: '#F472B6', icon: Clapperboard },
  Subscriptions: { color: '#A78BFA', icon: Repeat },
  Travel: { color: '#38BDF8', icon: Plane },
  Education: { color: '#FACC15', icon: GraduationCap },
  'Personal Care': { color: '#FDA4AF', icon: Scissors },
  'Gifts & Donations': { color: '#FB7185', icon: Gift },
  'Investments & Savings': { color: '#34D399', icon: PiggyBank },
  Transfers: { color: '#94A3B8', icon: ArrowLeftRight },
  'Self Transfer': { color: '#2DD4BF', icon: Landmark },
  'Card Bill Payment': { color: '#60A5FA', icon: CreditCard },
  'Fees & Charges': { color: '#F87171', icon: Receipt },
  Other: { color: '#7C86A2', icon: Ellipsis },
  [UNCATEGORIZED]: { color: '#4B5574', icon: CircleQuestionMark },
};

export const categoryMeta = (c: string | null | undefined): Meta => META[c ?? UNCATEGORIZED] ?? META.Other;

export function CategoryIcon({ category, size = 36 }: { category: string | null; size?: number }) {
  const { color, icon: Icon } = categoryMeta(category);
  return (
    <span
      className="cat-icon"
      style={{ width: size, height: size, color, background: `${color}1F`, boxShadow: `inset 0 0 0 1px ${color}33` }}
    >
      <Icon size={Math.round(size * 0.47)} strokeWidth={2} />
    </span>
  );
}
