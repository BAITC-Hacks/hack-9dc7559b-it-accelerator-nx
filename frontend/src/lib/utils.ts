import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** Склейка классов для shadcn/ui-компонентов. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
