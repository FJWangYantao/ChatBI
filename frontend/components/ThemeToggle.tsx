'use client';
import { useTheme } from '@/contexts/ThemeContext';
import { useState, useEffect } from 'react';

export default function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  if (!mounted) {
    return (
      <button
        className="p-2 text-gray-600 hover:bg-gray-100"
        title="切换主题"
      >
        🌙
      </button>
    );
  }

  return (
    <button
      onClick={toggleTheme}
      className="p-2 text-gray-600 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
      title={theme === 'light' ? '切换到深色模式' : '切换到浅色模式'}
    >
      {theme === 'light' ? '🌙' : '☀️'}
    </button>
  );
}
