import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useAuth } from '../context/auth-context';
import { Bell, Check, CheckSquare, AlertTriangle, Info } from 'lucide-react';
import { getNotifications, markAsRead, markAllAsRead } from '../services/notifications';
import { type Notification } from '../types';

interface HeaderProps {
  title: string;
}

const Header: React.FC<HeaderProps> = ({ title }) => {
  const { user } = useAuth();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [unreadCount, setUnreadCount] = useState(0);
  const [isOpen, setIsOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [markingRead, setMarkingRead] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const fetchNotifications = useCallback(() => {
    return getNotifications().then((data) => {
      const sorted = [...data.notifications].sort((a, b) => {
        if (a.isRead !== b.isRead) {
          return a.isRead ? 1 : -1;
        }
        return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
      });
      setNotifications(sorted);
      setTotalCount(data.totalCount);
      setUnreadCount(data.unreadCount);
      setError(null);
    }).catch(() => {
      setError('Unable to refresh notifications.');
    });
  }, []);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Initial fetch and polling every 10 seconds
  useEffect(() => {
    fetchNotifications();
    const interval = setInterval(fetchNotifications, 10000);
    return () => clearInterval(interval);
  }, [fetchNotifications]);

  const handleMarkAsRead = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (markingRead) return;
    setMarkingRead(true);
    try {
      await markAsRead(id);
      setNotifications((prev) =>
        prev.map((n) => (n.id === id ? { ...n, isRead: true } : n))
      );
      setUnreadCount((prev) => Math.max(0, prev - 1));
      setError(null);
    } catch {
      setError('Unable to mark the notification as read.');
    } finally {
      setMarkingRead(false);
    }
  };

  const handleMarkAllAsRead = async () => {
    if (markingRead) return;
    setMarkingRead(true);
    try {
      await markAllAsRead();
      setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
      setUnreadCount(0);
      setError(null);
    } catch {
      setError('Unable to mark notifications as read.');
    } finally {
      setMarkingRead(false);
    }
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + ' - ' + date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  };

  return (
    <header className="h-16 bg-[#0c101b]/80 backdrop-blur-md border-b border-gray-800 flex items-center justify-between gap-3 px-4 md:px-8 sticky top-0 z-40">
      <h2 className="text-lg sm:text-xl min-w-0 truncate font-bold text-white tracking-tight m-0">{title}</h2>

      <div className="flex shrink-0 items-center gap-6">
        {/* Notification Center */}
        <div className="relative" ref={dropdownRef}>
          <button
            aria-label="Notifications"
            aria-expanded={isOpen}
            onClick={() => setIsOpen(!isOpen)}
            className="relative p-2 text-gray-400 hover:text-white hover:bg-gray-800/50 rounded-lg transition-all duration-200 cursor-pointer"
          >
            <Bell className="h-5.5 w-5.5" />
            {unreadCount > 0 && (
              <span className="absolute top-1 right-1 h-5 min-w-[20px] px-1 bg-indigo-600 text-[10px] font-bold text-white rounded-full flex items-center justify-center border-2 border-[#0c101b] animate-pulse">
                {unreadCount}
              </span>
            )}
          </button>

          {/* Notifications Dropdown */}
          {isOpen && (
            <div className="absolute right-0 mt-2 w-80 max-w-[calc(100vw-2rem)] bg-[#0c101b] rounded-xl shadow-2xl overflow-hidden z-50 border border-gray-800">
              <div className="p-4 border-b border-gray-800 flex items-center justify-between bg-gray-900/30">
                <span className="font-semibold text-sm text-white">Notifications (Total: {totalCount})</span>
                {unreadCount > 0 && (
                  <button
                    onClick={handleMarkAllAsRead}
                    disabled={markingRead}
                    className="text-[11px] font-medium text-indigo-400 hover:text-indigo-300 flex items-center gap-1 cursor-pointer"
                  >
                    <CheckSquare className="h-3.5 w-3.5" />
                    <span>Read All</span>
                  </button>
                )}
              </div>

              {/* Notification List */}
              <div className="max-h-72 overflow-y-auto divide-y divide-gray-850">
                {error && <p role="status" className="p-4 text-xs text-amber-400">{error}</p>}
                {notifications.length === 0 ? (
                  <div className="p-6 text-center text-gray-500 text-xs">
                    No notifications yet
                  </div>
                ) : (
                  notifications.map((notification) => {
                    const isLowStock = notification.type === 'LOW_STOCK';
                    return (
                      <div
                        key={notification.id}
                        className={`p-4 flex gap-3 text-left transition duration-150 ${
                          notification.isRead ? 'bg-transparent' : 'bg-indigo-600/5'
                        }`}
                      >
                        {/* Icon */}
                        <div
                          className={`h-7 w-7 rounded-lg flex items-center justify-center shrink-0 mt-0.5 ${
                            isLowStock
                              ? 'bg-amber-500/10 text-amber-400 border border-amber-500/20'
                              : 'bg-indigo-500/10 text-indigo-400 border border-indigo-500/20'
                          }`}
                        >
                          {isLowStock ? (
                            <AlertTriangle className="h-4 w-4" />
                          ) : (
                            <Info className="h-4 w-4" />
                          )}
                        </div>

                        {/* Content */}
                        <div className="flex-1 min-w-0">
                          <p className="text-xs text-gray-200 leading-normal mb-1 font-medium break-words">
                            {notification.message}
                          </p>
                          <span className="text-[9px] text-gray-500 font-mono">
                            {formatDate(notification.createdAt)}
                          </span>
                        </div>

                        {/* Action: Mark as read */}
                        {!notification.isRead && (
                          <button
                            onClick={(e) => handleMarkAsRead(notification.id, e)}
                            className="h-6 w-6 rounded hover:bg-gray-800 text-gray-500 hover:text-white flex items-center justify-center shrink-0 cursor-pointer"
                            title="Mark as read"
                            disabled={markingRead}
                          >
                            <Check className="h-3.5 w-3.5" />
                          </button>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </div>

        {/* User Info Capsule */}
        <div className="hidden sm:flex items-center gap-3 pl-4 border-l border-gray-800">
          <div className="h-8 w-8 rounded-lg bg-indigo-600/10 border border-indigo-500/20 flex items-center justify-center font-semibold text-indigo-400 text-xs select-none">
            {user?.role === 'ROLE_ADMIN' ? 'AD' : 'ST'}
          </div>
          <div className="text-left hidden md:block">
            <p className="text-xs font-semibold text-white leading-none m-0">
              {user?.fullName.split(' ')[0]}
            </p>
            <span className="text-[9px] text-gray-400 font-mono tracking-wider mt-1 block leading-none">
              {user?.role}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
};

export default Header;
