import React from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { 
  LayoutDashboard, 
  Package, 
  FileSpreadsheet, 
  History, 
  LogOut, 
  ShieldCheck,
  Users
} from 'lucide-react';

const Sidebar: React.FC = () => {
  const { user, logout, isAdmin } = useAuth();

  const menuItems = [
    {
      name: 'Dashboard',
      path: '/dashboard',
      icon: LayoutDashboard,
      show: isAdmin,
    },
    {
      name: 'User Management',
      path: '/users',
      icon: Users,
      show: isAdmin,
    },
    {
      name: 'Product Catalog',
      path: '/catalog',
      icon: Package,
      show: true,
    },
    {
      name: 'Purchase Orders',
      path: '/orders',
      icon: FileSpreadsheet,
      show: true,
    },
    {
      name: 'Audit Logs',
      path: '/audit-logs',
      icon: History,
      show: isAdmin,
    },
  ];

  return (
    <aside className="w-64 bg-[#0c101b] border-r border-gray-800 flex flex-col h-screen sticky top-0 shrink-0">
      {/* Brand Header */}
      <div className="h-16 flex items-center gap-3 px-6 border-b border-gray-800/60">
        <div className="h-8 w-8 rounded-lg bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
          <ShieldCheck className="h-5 w-5" />
        </div>
        <span className="font-bold text-white text-base tracking-wide">Nexus Hub</span>
      </div>

      {/* Navigation Links */}
      <nav className="flex-1 px-4 py-6 space-y-1.5 overflow-y-auto">
        {menuItems
          .filter((item) => item.show)
          .map((item) => {
            const Icon = item.icon;
            return (
              <NavLink
                key={item.name}
                to={item.path}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-all duration-200 cursor-pointer ${
                    isActive
                      ? 'bg-indigo-600/15 text-indigo-400 border border-indigo-500/20 shadow-inner'
                      : 'text-gray-400 hover:bg-gray-850 hover:text-white border border-transparent'
                  }`
                }
              >
                <Icon className="h-5 w-5 shrink-0" />
                <span>{item.name}</span>
              </NavLink>
            );
          })}
      </nav>

      {/* User Info & Logout Footer */}
      <div className="p-4 border-t border-gray-800/60 bg-[#0a0d16]/50">
        <div className="flex items-center gap-3 mb-4 px-2">
          <div className="h-9 w-9 rounded-full bg-indigo-500/10 border border-indigo-500/25 flex items-center justify-center font-bold text-indigo-400 text-sm">
            {user?.fullName.split(' ').map(n => n[0]).join('') || 'U'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs font-semibold text-white truncate m-0">{user?.fullName}</p>
            <p className="text-[10px] text-indigo-400 font-mono mt-0.5 truncate uppercase">
              {user?.role?.replace('ROLE_', '') || ''}
            </p>
          </div>
        </div>
        
        <button
          onClick={logout}
          className="w-full flex items-center gap-3 px-4 py-2.5 rounded-lg text-sm font-medium text-red-400 hover:bg-red-500/10 hover:text-red-300 border border-transparent hover:border-red-500/20 transition-all duration-200 cursor-pointer"
        >
          <LogOut className="h-5 w-5 shrink-0" />
          <span>Sign Out</span>
        </button>
      </div>
    </aside>
  );
};

export default Sidebar;
