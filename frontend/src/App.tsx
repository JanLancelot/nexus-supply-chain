import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Catalog from './pages/Catalog';
import Orders from './pages/Orders';
import AuditLogs from './pages/AuditLogs';
import Sidebar from './components/Sidebar';
import Header from './components/Header';
import './App.css';

// Route protection component for authenticated users
const ProtectedRoute: React.FC<{ children: React.ReactNode; adminOnly?: boolean }> = ({ 
  children, 
  adminOnly = false 
}) => {
  const { isAuthenticated, isAdmin, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen w-full flex flex-col items-center justify-center bg-[#070b13] text-gray-400">
        <div className="h-10 w-10 border-4 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin mb-4" />
        <span className="text-sm font-medium tracking-wider">Verifying Session...</span>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (adminOnly && !isAdmin) {
    // Non-admin trying to access admin page: redirect to catalog
    return <Navigate to="/catalog" replace />;
  }

  return <>{children}</>;
};

// Global Layout wrapper that puts header/sidebar around pages
interface LayoutWrapperProps {
  children: React.ReactNode;
  title: string;
}

const LayoutWrapper: React.FC<LayoutWrapperProps> = ({ children, title }) => {
  return (
    <div className="flex min-h-screen bg-[#070b13] text-gray-200">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0">
        <Header title={title} />
        <main className="flex-1 p-8 overflow-y-auto">
          {children}
        </main>
      </div>
    </div>
  );
};

// Main routing configuration
const AppRoutes: React.FC = () => {
  const { isAdmin, isAuthenticated } = useAuth();

  return (
    <Routes>
      {/* Login Screen */}
      <Route path="/login" element={
        isAuthenticated ? <Navigate to="/" replace /> : <Login />
      } />

      {/* Main Panel Routes */}
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute adminOnly>
            <LayoutWrapper title="Operations Dashboard">
              <Dashboard />
            </LayoutWrapper>
          </ProtectedRoute>
        }
      />

      <Route
        path="/catalog"
        element={
          <ProtectedRoute>
            <LayoutWrapper title="Product Catalog">
              <Catalog />
            </LayoutWrapper>
          </ProtectedRoute>
        }
      />

      <Route
        path="/orders"
        element={
          <ProtectedRoute>
            <LayoutWrapper title="Purchase Orders">
              <Orders />
            </LayoutWrapper>
          </ProtectedRoute>
        }
      />

      <Route
        path="/audit-logs"
        element={
          <ProtectedRoute adminOnly>
            <LayoutWrapper title="Forensic Audit Logs">
              <AuditLogs />
            </LayoutWrapper>
          </ProtectedRoute>
        }
      />

      {/* Root Path Redirection */}
      <Route
        path="/"
        element={
          <ProtectedRoute>
            {isAdmin ? <Navigate to="/dashboard" replace /> : <Navigate to="/catalog" replace />}
          </ProtectedRoute>
        }
      />

      {/* Wildcard Fallback */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
};

const App: React.FC = () => {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
};

export default App;
