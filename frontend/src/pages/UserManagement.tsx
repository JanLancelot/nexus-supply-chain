import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { 
  UserPlus, 
  Users, 
  Search, 
  Mail, 
  Lock, 
  UserCheck, 
  ShieldAlert, 
  RefreshCw, 
  X,
  ShieldCheck,
  User as UserIcon
} from 'lucide-react';
import { getUsers, createUser, type UserCreateData } from '../services/users';
import { type User } from '../types';

const UserManagement: React.FC = () => {
  useAuth();

  // State variables
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Form State
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [roleName, setRoleName] = useState('ROLE_STAFF');

  // Search & Filter State
  const [searchTerm, setSearchTerm] = useState('');
  const [roleFilter, setRoleFilter] = useState('ALL');

  // Validation errors
  const [validationErrors, setValidationErrors] = useState<{
    fullName?: string;
    email?: string;
    password?: string;
  }>({});

  const fetchUsers = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getUsers();
      // Sort users by name
      const sorted = [...data].sort((a, b) => a.fullName.localeCompare(b.fullName));
      setUsers(sorted);
    } catch (err: any) {
      console.error(err);
      setError('Access Denied or Failed to fetch users directory. Confirm administrative session permissions.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchUsers();
  }, []);

  const validateForm = () => {
    const errors: typeof validationErrors = {};
    if (!fullName.trim()) {
      errors.fullName = 'Full name is required';
    }
    if (!email.trim()) {
      errors.email = 'Email address is required';
    } else if (!/\S+@\S+\.\S+/.test(email)) {
      errors.email = 'Please enter a valid email address';
    }
    if (!password) {
      errors.password = 'Password is required';
    } else if (password.length < 8) {
      errors.password = 'Password must be at least 8 characters long';
    }

    setValidationErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleCreateUser = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateForm()) return;

    setSubmitting(true);
    setError(null);
    setSuccess(null);

    const newUserData: UserCreateData = {
      fullName: fullName.trim(),
      email: email.trim().toLowerCase(),
      password,
      roleName
    };

    try {
      await createUser(newUserData);
      setSuccess(`Account for "${fullName}" was successfully created!`);
      // Reset form
      setFullName('');
      setEmail('');
      setPassword('');
      setRoleName('ROLE_STAFF');
      setValidationErrors({});
      // Refresh directory list
      await fetchUsers();
    } catch (err: any) {
      console.error(err);
      setError(err.response?.data?.message || err.message || 'An error occurred while creating the user.');
    } finally {
      setSubmitting(false);
    }
  };

  // Helper to format role names dynamically
  const formatRole = (role?: string) => {
    if (!role) return '';
    return role.replace('ROLE_', '');
  };

  // Helper to render role badge styles
  const getRoleBadgeStyle = (role?: string) => {
    if (role === 'ROLE_ADMIN') {
      return 'bg-red-500/10 text-red-400 border-red-500/20';
    }
    return 'bg-indigo-500/10 text-indigo-400 border-indigo-500/20';
  };

  const filteredUsers = users.filter((user) => {
    const matchesSearch = 
      user.fullName.toLowerCase().includes(searchTerm.toLowerCase()) || 
      user.email.toLowerCase().includes(searchTerm.toLowerCase());
    
    const matchesRole = 
      roleFilter === 'ALL' || 
      user.role === roleFilter;

    return matchesSearch && matchesRole;
  });

  return (
    <div className="space-y-6">
      {/* Alert Banners */}
      {error && (
        <div className="p-4 bg-red-950/40 border border-red-500/25 text-red-300 text-sm rounded-xl flex items-center justify-between animate-fade-in">
          <div className="flex items-center gap-3">
            <ShieldAlert className="h-5 w-5 text-red-400 shrink-0" />
            <span>{error}</span>
          </div>
          <button onClick={() => setError(null)} className="text-red-400 hover:text-white cursor-pointer"><X className="h-4 w-4" /></button>
        </div>
      )}

      {success && (
        <div className="p-4 bg-emerald-950/40 border border-emerald-500/25 text-emerald-300 text-sm rounded-xl flex items-center justify-between animate-fade-in">
          <div className="flex items-center gap-3">
            <ShieldCheck className="h-5 w-5 text-emerald-400 shrink-0" />
            <span>{success}</span>
          </div>
          <button onClick={() => setSuccess(null)} className="text-emerald-400 hover:text-white cursor-pointer"><X className="h-4 w-4" /></button>
        </div>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6 items-start">
        {/* Left column: User Directory */}
        <div className="xl:col-span-2 space-y-6">
          <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4 glass-panel p-5 rounded-2xl">
            {/* Search Bar */}
            <div className="relative flex-1 max-w-sm">
              <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
                <Search className="h-4 w-4" />
              </span>
              <input
                type="text"
                placeholder="Search by name or email..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-9 pr-4 py-2 text-xs glass-input"
              />
            </div>

            {/* Filter controls */}
            <div className="flex items-center gap-3">
              <select
                value={roleFilter}
                onChange={(e) => setRoleFilter(e.target.value)}
                className="px-3 py-2 text-xs glass-input cursor-pointer"
              >
                <option value="ALL">All Roles</option>
                <option value="ROLE_ADMIN">Administrators</option>
                <option value="ROLE_STAFF">Operations Staff</option>
              </select>

              <button
                onClick={fetchUsers}
                className="p-2 text-gray-400 hover:text-white hover:bg-gray-800/40 rounded-lg border border-gray-800 hover:border-gray-700 transition cursor-pointer"
                title="Refresh Directory"
              >
                <RefreshCw className="h-4 w-4" />
              </button>
            </div>
          </div>

          {loading ? (
            <div className="py-20 flex flex-col items-center justify-center">
              <div className="h-8 w-8 border-4 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
              <p className="text-sm text-gray-400">Loading user registry...</p>
            </div>
          ) : filteredUsers.length === 0 ? (
            <div className="py-20 text-center glass-panel rounded-2xl">
              <Users className="h-10 w-10 text-gray-600 mx-auto mb-3" />
              <h4 className="text-sm font-semibold text-white m-0">No Users Found</h4>
              <p className="text-xs text-gray-500 mt-1">Try modifying your filters or search criteria.</p>
            </div>
          ) : (
            <div className="glass-panel rounded-2xl overflow-hidden border border-gray-800/60 shadow-lg">
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse text-xs">
                  <thead>
                    <tr className="bg-gray-900/40 text-gray-400 text-[10px] font-semibold uppercase tracking-wider border-b border-gray-850">
                      <th className="py-4 px-6">User Profile</th>
                      <th className="py-4 px-6">Email Address</th>
                      <th className="py-4 px-6">System Role</th>
                      <th className="py-4 px-6">Account Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-850">
                    {filteredUsers.map((userItem) => (
                      <tr 
                        key={userItem.id} 
                        className="hover:bg-gray-900/10 transition duration-150"
                      >
                        <td className="py-4 px-6">
                          <div className="flex items-center gap-3">
                            <div className="h-8 w-8 rounded-full bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center font-bold text-indigo-400 text-xs">
                              {userItem.fullName.split(' ').map(n => n[0]).join('') || 'U'}
                            </div>
                            <span className="font-semibold text-white">{userItem.fullName}</span>
                          </div>
                        </td>
                        <td className="py-4 px-6 text-gray-400 font-mono">{userItem.email}</td>
                        <td className="py-4 px-6">
                          <span className={`inline-block px-2 py-0.5 rounded text-[9px] font-bold border uppercase ${getRoleBadgeStyle(userItem.role)}`}>
                            {formatRole(userItem.role)}
                          </span>
                        </td>
                        <td className="py-4 px-6">
                          <div className="flex items-center gap-1.5">
                            <div className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                            <span className="text-gray-300 font-medium">Active</span>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>

        {/* Right column: Create User Form Card */}
        <div className="xl:col-span-1 glass-panel p-6 rounded-2xl border border-gray-850 space-y-6">
          <div className="flex items-center gap-3 border-b border-gray-850 pb-4">
            <div className="p-2 bg-indigo-500/10 rounded-lg text-indigo-400 border border-indigo-500/20">
              <UserPlus className="h-5 w-5" />
            </div>
            <div>
              <h3 className="font-bold text-sm text-white m-0">Provision New User</h3>
              <p className="text-[10px] text-gray-500 m-0 mt-0.5">Register administrative or operational credentials.</p>
            </div>
          </div>

          <form onSubmit={handleCreateUser} className="space-y-4" noValidate>
            {/* Full Name */}
            <div className="space-y-1.5">
              <label className="text-[11px] font-bold text-gray-400 uppercase tracking-wider block">Full Name</label>
              <div className="relative">
                <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
                  <UserIcon className="h-4 w-4" />
                </span>
                <input
                  type="text"
                  placeholder="e.g. Jane Doe"
                  value={fullName}
                  onChange={(e) => {
                    setFullName(e.target.value);
                    if (validationErrors.fullName) {
                      setValidationErrors(prev => ({ ...prev, fullName: undefined }));
                    }
                  }}
                  className={`w-full pl-9 pr-4 py-2.5 text-xs glass-input ${validationErrors.fullName ? 'border-red-500/50 focus:border-red-500/75' : ''}`}
                />
              </div>
              {validationErrors.fullName && (
                <span className="text-[10px] text-red-400 block mt-1 font-medium">{validationErrors.fullName}</span>
              )}
            </div>

            {/* Email Address */}
            <div className="space-y-1.5">
              <label className="text-[11px] font-bold text-gray-400 uppercase tracking-wider block">Email Address</label>
              <div className="relative">
                <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
                  <Mail className="h-4 w-4" />
                </span>
                <input
                  type="email"
                  placeholder="name@company.com"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    if (validationErrors.email) {
                      setValidationErrors(prev => ({ ...prev, email: undefined }));
                    }
                  }}
                  className={`w-full pl-9 pr-4 py-2.5 text-xs glass-input ${validationErrors.email ? 'border-red-500/50 focus:border-red-500/75' : ''}`}
                />
              </div>
              {validationErrors.email && (
                <span className="text-[10px] text-red-400 block mt-1 font-medium">{validationErrors.email}</span>
              )}
            </div>

            {/* Password */}
            <div className="space-y-1.5">
              <label className="text-[11px] font-bold text-gray-400 uppercase tracking-wider block">Password</label>
              <div className="relative">
                <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
                  <Lock className="h-4 w-4" />
                </span>
                <input
                  type="password"
                  placeholder="Min 8 characters"
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value);
                    if (validationErrors.password) {
                      setValidationErrors(prev => ({ ...prev, password: undefined }));
                    }
                  }}
                  className={`w-full pl-9 pr-4 py-2.5 text-xs glass-input ${validationErrors.password ? 'border-red-500/50 focus:border-red-500/75' : ''}`}
                />
              </div>
              {validationErrors.password && (
                <span className="text-[10px] text-red-400 block mt-1 font-medium">{validationErrors.password}</span>
              )}
            </div>

            {/* Role Selection */}
            <div className="space-y-1.5">
              <label className="text-[11px] font-bold text-gray-400 uppercase tracking-wider block">System Role</label>
              <select
                value={roleName}
                onChange={(e) => setRoleName(e.target.value)}
                className="w-full px-3 py-2.5 text-xs glass-input cursor-pointer"
              >
                <option value="ROLE_STAFF">Operations Staff</option>
                <option value="ROLE_ADMIN">Administrator</option>
              </select>
              <p className="text-[10px] text-gray-500 leading-relaxed pt-1">
                Admins possess full read/write capabilities across catalog, logistics, and audit. Staff users possess operational catalog and PO flow access.
              </p>
            </div>

            {/* Submit Button */}
            <button
              type="submit"
              disabled={submitting}
              className="w-full mt-4 flex items-center justify-center gap-2 px-4 py-3 rounded-lg text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 disabled:opacity-45 text-white shadow-lg shadow-indigo-600/20 border border-indigo-500/20 hover:border-indigo-400 transition-all duration-200 cursor-pointer"
            >
              {submitting ? (
                <>
                  <RefreshCw className="h-4 w-4 animate-spin" />
                  <span>Provisioning Account...</span>
                </>
              ) : (
                <>
                  <UserCheck className="h-4 w-4" />
                  <span>Create User Account</span>
                </>
              )}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
};

export default UserManagement;
