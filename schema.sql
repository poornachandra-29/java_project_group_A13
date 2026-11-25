-- ===================================================================
-- Social Chat App - Database Schema
-- Database: socialchatapp
-- ===================================================================

CREATE DATABASE IF NOT EXISTS `socialchatapp` 
    DEFAULT CHARACTER SET utf8mb4 
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `socialchatapp`;

-- -------------------------------------------------------------------
-- 1. Table: users
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `password` VARCHAR(255) NOT NULL, -- BCrypt hashed password
    `avatar` LONGBLOB DEFAULT NULL,
    `avatar_mime` VARCHAR(50) DEFAULT NULL,
    `online` BOOLEAN DEFAULT FALSE,
    `last_seen` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `avatar_updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------------
-- 2. Table: messages (Direct Messages)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `messages` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `sender` VARCHAR(50) NOT NULL,
    `recipient` VARCHAR(50) NOT NULL,
    `body` TEXT NOT NULL,
    `reply_to` BIGINT DEFAULT NULL,
    `status` ENUM('queued', 'delivered') DEFAULT 'queued',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `delivered_at` TIMESTAMP NULL DEFAULT NULL,
    `updated_at` TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_messages_sender` FOREIGN KEY (`sender`) REFERENCES `users` (`username`) ON DELETE CASCADE,
    CONSTRAINT `fk_messages_recipient` FOREIGN KEY (`recipient`) REFERENCES `users` (`username`) ON DELETE CASCADE,
    CONSTRAINT `fk_messages_reply_to` FOREIGN KEY (`reply_to`) REFERENCES `messages` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------------
-- 3. Table: files (Media & File Attachments)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `files` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `message_id` BIGINT NOT NULL,
    `file_name` VARCHAR(255) NOT NULL,
    `file_path` VARCHAR(500) NOT NULL,
    `mime_type` VARCHAR(100) NOT NULL,
    `file_size` BIGINT NOT NULL,
    `uploaded_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_files_message` FOREIGN KEY (`message_id`) REFERENCES `messages` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------------
-- 4. Table: groups
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `groups` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL,
    `owner` VARCHAR(50) NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_groups_owner` FOREIGN KEY (`owner`) REFERENCES `users` (`username`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------------
-- 5. Table: group_members
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `group_members` (
    `group_id` INT NOT NULL,
    `username` VARCHAR(50) NOT NULL,
    `joined_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`group_id`, `username`),
    CONSTRAINT `fk_gm_group` FOREIGN KEY (`group_id`) REFERENCES `groups` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_gm_user` FOREIGN KEY (`username`) REFERENCES `users` (`username`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------------
-- 6. Table: group_messages
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `group_messages` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `group_id` INT NOT NULL,
    `sender` VARCHAR(50) NOT NULL,
    `body` TEXT NOT NULL,
    `reply_to` BIGINT DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_gm_messages_group` FOREIGN KEY (`group_id`) REFERENCES `groups` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_gm_messages_sender` FOREIGN KEY (`sender`) REFERENCES `users` (`username`) ON DELETE CASCADE,
    CONSTRAINT `fk_gm_messages_reply_to` FOREIGN KEY (`reply_to`) REFERENCES `group_messages` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
