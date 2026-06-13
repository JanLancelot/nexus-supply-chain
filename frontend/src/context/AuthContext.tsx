import React, { createContext, useState, useEffect, useContext } from 'react';
import api from '../services/api';
import { type User } from '../types';

interface AuthContextType {
  user: User | null;
  token: string | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  isAuthenticated: boolean;
  isAdmin: boolean;
  isStaff: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

// Helper function to decode JWT payload safely without external dependencies
const decodeToken = (token: string) => {
  try {
    const payloadBase64 = token.split('.')[1];
    if (!payloadBase64) return null;
    const decodedJson = atob(payloadBase64.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decodedJson);
  } catch (e) {
    console.error('Failed to decode JWT token:', e);
    return null;
  }
};

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Initialize session from localStorage on application load
  useEffect(() => {
    const storedToken = localStorage.getItem('token');
    if (storedToken) {
      const decoded = decodeToken(storedToken);
      if (decoded && decoded.exp * 1000 > Date.now()) {
        setToken(storedToken);
        setUser({
          id: decoded.userId,
          fullName: decoded.sub === 'admin@pg.com' ? 'Admin User' : decoded.sub === 'staff@pg.com' ? 'Staff User' : 'Nexus User',
          email: decoded.sub,
          role: decoded.role,
          status: 'ACTIVE',
        });
      } else {
        // Token has expired or is invalid
        localStorage.removeItem('token');
      }
    }
    setLoading(false);
  }, []);

  const login = async (email: string, password: string) => {
    try {
      const response = await api.post('/auth/login', { email, password });
      const { token } = response.data;
      
      localStorage.setItem('token', token);
      setToken(token);

      const decoded = decodeToken(token);
      if (decoded) {
        setUser({
          id: decoded.userId,
          fullName: decoded.sub === 'admin@pg.com' ? 'Admin User' : decoded.sub === 'staff@pg.com' ? 'Staff User' : 'Nexus User',
          email: decoded.sub,
          role: decoded.role,
          status: 'ACTIVE',
        });
      }
    } catch (error: any) {
      console.error('Login error:', error);
      throw error.response?.data?.message || 'Invalid email or password';
    }
  };

  const logout = () => {
    localStorage.removeItem('token');
    setToken(null);
    setUser(null);
  };

  const isAuthenticated = !!token;
  const isAdmin = user?.role === 'ROLE_ADMIN';
  const isStaff = user?.role === 'ROLE_STAFF';

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        loading,
        login,
        logout,
        isAuthenticated,
        isAdmin,
        isStaff,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
